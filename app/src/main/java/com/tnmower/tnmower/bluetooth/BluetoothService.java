package com.tnmower.tnmower.bluetooth;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.tnmower.tnmower.utils.CRCUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import android.annotation.SuppressLint;

@SuppressLint("MissingPermission")
public class BluetoothService extends Service {
    private final AtomicBoolean serviceAlive = new AtomicBoolean(true);
    private volatile long currentConnectSession = 0;
    private static final String CHANNEL_ID = "TN_MOWER_BT";
    private static final long RX_TIMEOUT = 3000;
    private static final long RESEND_INTERVAL = 300;
    private static final int MAX_RETRY = 3;
    private static OnTelemetryListener telemetryListener;
    private final UUID UUID_SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private final AtomicBoolean isStopping = new AtomicBoolean(false); // 🔴 กัน stopSelf ซ้ำ
    private final AtomicBoolean processingQueue = new AtomicBoolean(false);
    private final IBinder binder = new LocalBinder();
    private final ConcurrentLinkedQueue<Byte> commandQueue = new ConcurrentLinkedQueue<>();
    private BluetoothAdapter btAdapter;
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;
    private String MAC = "00:21:13:00:00:00";
    private String lastStatus = "";
    private long lastRxTime = 0;
    // =========================
    private int lastSeq = -1;
    private int txSeq = 0;
    private int waitingAck = -1;
    private int reconnectFailCount = 0; // 🔴 นับจำนวน reconnect fail
    // 🔴 Industrial control
    private long lastBtResetTime = 0;
    private static final long BT_RESET_COOLDOWN = 15000; // กัน reset ถี่

    private String lastFailedMAC = "";
    private int sameDeviceFailCount = 0;

    private static final int MAX_FAIL_BEFORE_BLOCK = 3;
    private static final int MAX_FAIL_BEFORE_BT_RESET = 6;
    private long lastCmdTime = 0;
    private int retryCount = 0;
    private byte lastCmdSent = 0;
    private Thread rxThread = null;
    // 🔴 FIX: กัน connect ซ้อน / race



    public static void setTelemetryListener(OnTelemetryListener listener) {
        telemetryListener = listener;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // =========================
    @Override
    public void onCreate() {
        super.onCreate();

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        startForegroundService();
        // 🔴 กัน thread ซ้อน
        if (!running.get()) {
            running.set(true);

            new Thread(() -> {
                try {
                    connectionLoop();
                } catch (Throwable ignored) {}
            }, "BT-CONN").start();
        }
    }

    // =========================
    // 🔴 รับ MAC จาก Activity
    // =========================
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // =========================
        // 🔴 RESET STATE ก่อน connect ใหม่ (สำคัญมาก)
        // =========================
        connected.set(false);
        waitingAck = -1;
        retryCount = 0;
        commandQueue.clear();

        safeClose();  // 🔥 ปิด socket เก่าทันที

        // =========================
        // 🔴 รับ MAC ใหม่
        // =========================
        if (intent != null) {
            String macFromIntent = intent.getStringExtra("MAC");

            if (macFromIntent != null && !macFromIntent.isEmpty()) {
                MAC = macFromIntent;
            }
        }

        return START_NOT_STICKY;
    }

    // =========================
    private void startForegroundService() {

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TN Mower BT",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TN Mower")
                .setContentText("Bluetooth Running")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();

        startForeground(1, notification);
    }

    public void sendStop() {
        sendPriorityCommand((byte) 0x10);
    }

    // =========================
    private void connectionLoop() {

        long lastReconnectAttempt = 0;

        while (running.get() && serviceAlive.get()) {

            try {

                long now = System.currentTimeMillis();

                // =========================
                // 🔴 WATCHDOG (RX หาย)
                // =========================
                if (connected.get() && (now - lastRxTime > RX_TIMEOUT)) {

                    sendStatus("WATCHDOG_TIMEOUT");

                    connected.set(false);
                    waitingAck = -1;
                    retryCount = 0;
                    commandQueue.clear();

                    safeClose();
                }

                // =========================
                // 🔴 RESEND COMMAND
                // =========================
                if (connected.get() && waitingAck != -1) {

                    if (now - lastCmdTime > RESEND_INTERVAL) {

                        if (retryCount < MAX_RETRY) {

                            retryCount++;

                            try {
                                sendCommandInternal(lastCmdSent, true);
                            } catch (Throwable t) {

                                sendStatus("CMD_ERROR");

                                // 🔴 HARD RESET
                                connected.set(false);
                                waitingAck = -1;
                                retryCount = 0;

                                safeClose();
                            }

                        } else {

                            sendStatus("CMD_FAILED");

                            waitingAck = -1;
                            retryCount = 0;

                            processQueue();
                        }
                    }
                }

                // =========================
                // 🔴 AUTO RECONNECT (SAFE)
                // =========================
                if (!connected.get() && !connecting.get() && socket == null) {

                    long delay;

                    if (reconnectFailCount < 3) delay = 3000;
                    else if (reconnectFailCount < 6) delay = 5000;
                    else delay = 10000;

                    if (now - lastReconnectAttempt > delay) {

                        lastReconnectAttempt = now;

                        try {

                            // 🔴 HARD RESET ก่อน connect ใหม่
                            safeClose();
                            waitingAck = -1;
                            retryCount = 0;
                            commandQueue.clear();

                            sendStatus("RECONNECTING");

                            connect();

                            if (connected.get()) {
                                reconnectFailCount = 0;
                            } else {
                                reconnectFailCount++;
                            }

                        } catch (Throwable t) {

                            reconnectFailCount++;

                            sendStatus("RECONNECT_FAIL");

                            connected.set(false);

                            safeClose();
                        }
                    }
                }

            } catch (Throwable t) {

                // 🔴 LOOP ไม่ให้ตายเด็ดขาด
                sendStatus("FATAL_LOOP");

                connected.set(false);

                try {
                    safeClose();
                } catch (Exception ignored) {}

                SystemClock.sleep(500); // 🔴 หน่วงกัน crash loop
            }

            SystemClock.sleep(100);
        }
    }

    // =========================
// 🔴 FUNCTION: connect() (FINAL - NO CRASH / NO HANG)
// FILE: BluetoothService.java
// =========================
    @SuppressLint("MissingPermission")
    private synchronized void connect() {

        if (connected.get() || connecting.get()) return;

        isStopping.set(false);

        final long sessionId = System.currentTimeMillis();
        currentConnectSession = sessionId;

        connecting.set(true);
        connected.set(false);

        waitingAck = -1;
        retryCount = 0;

        safeClose();
        SystemClock.sleep(200);

        try {
            if (rxThread != null && rxThread.isAlive()) {
                rxThread.interrupt();
                rxThread = null;
            }
        } catch (Exception ignored) {}

        // =========================
        // 🔴 Permission
        // =========================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {

                sendStatus("NO_PERMISSION");
                connecting.set(false);
                return;
            }
        }

        sendStatus("CONNECTING");

        if (btAdapter == null || !btAdapter.isEnabled()) {
            sendStatus("BT_OFF");
            connecting.set(false);
            return;
        }

        try {
            if (btAdapter.isDiscovering()) {
                btAdapter.cancelDiscovery();
            }
        } catch (Throwable ignored) {}

        BluetoothDevice device;

        try {
            device = btAdapter.getRemoteDevice(MAC);
        } catch (Throwable e) {
            sendStatus("INVALID_MAC");
            connecting.set(false);
            return;
        }

        // =========================
        // 🔴 FILTER DEVICE (ปรับใหม่)
        // =========================
        String name = null;
        int type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;

        try { name = device.getName(); } catch (Throwable ignored) {}
        try { type = device.getType(); } catch (Throwable ignored) {}

        // 🔴 กัน BLE
        if (type == BluetoothDevice.DEVICE_TYPE_LE) {
            sendStatus("BLE_DEVICE");
            connecting.set(false);
            return;
        }

        // 🔴 กัน device แปลก (แต่ไม่ล็อค HC อย่างเดียว)
        if (type != BluetoothDevice.DEVICE_TYPE_CLASSIC &&
                type != BluetoothDevice.DEVICE_TYPE_DUAL) {

            sendStatus("INVALID_DEVICE");
            connecting.set(false);
            return;
        }

        // =========================
        // 🔴 BLACKLIST
        // =========================
        if (MAC.equals(lastFailedMAC) && sameDeviceFailCount >= MAX_FAIL_BEFORE_BLOCK) {
            sendStatus("DEVICE_BLOCKED");
            connecting.set(false);
            return;
        }

        BluetoothSocket tmpSocket = null;

        try {
            tmpSocket = device.createRfcommSocketToServiceRecord(UUID_SPP);
        } catch (Throwable ignored) {}

        if (tmpSocket == null) {
            try {
                tmpSocket = (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", int.class)
                        .invoke(device, 1);
            } catch (Throwable ignored) {}
        }

        if (tmpSocket == null) {
            sendStatus("SOCKET_FAIL");
            connecting.set(false);
            return;
        }

        final BluetoothSocket finalSocket = tmpSocket;

        Thread connectThread = new Thread(() -> {

            try {

                finalSocket.connect();

// 🔴 กัน service ตายระหว่าง connect
                if (!serviceAlive.get()) {
                    try { finalSocket.close(); } catch (Throwable ignored) {}
                    return;
                }

                if (sessionId != currentConnectSession) {
                    try { finalSocket.close(); } catch (Throwable ignored) {}
                    return;
                }

                if (!connecting.get()) {
                    try { finalSocket.close(); } catch (Throwable ignored) {}
                    return;
                }

                if (!finalSocket.isConnected()) {
                    connecting.set(false);
                    safeClose();
                    return;
                }

                // 🔴 SUCCESS
                sameDeviceFailCount = 0;
                lastFailedMAC = "";
                reconnectFailCount = 0;

                if (!serviceAlive.get()) {
                    try { finalSocket.close(); } catch (Throwable ignored) {}
                    return;
                }

                handleConnected(finalSocket);

            } catch (Throwable e) {

                sendStatus("CONNECT_FAIL");

                connecting.set(false);
                connected.set(false);

                try {
                    finalSocket.close();
                    SystemClock.sleep(100); // 🔴 FIX native ค้าง
                } catch (Throwable ignored) {}

                safeClose();

                reconnectFailCount++;

                // 🔴 TRACK FAIL
                if (MAC.equals(lastFailedMAC)) {
                    sameDeviceFailCount++;
                } else {
                    lastFailedMAC = MAC;
                    sameDeviceFailCount = 1;
                }

                // 🔴 RESET BT (กันพัง Android ใหม่)
                if (sameDeviceFailCount >= MAX_FAIL_BEFORE_BT_RESET) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        restartBluetoothAdapter();
                    }
                }

                if (reconnectFailCount >= 3) {

                    sendStatus("STOPPED");

                    new Thread(() -> {
                        SystemClock.sleep(300);
                        if (!isStopping.getAndSet(true)) {
                            try { stopSelf(); } catch (Exception ignored) {}
                        }
                    }).start();

                    return;
                }

                SystemClock.sleep(500);
            }

        }, "BT-CONNECT");

        connectThread.start();

        // =========================
        // 🔴 TIMEOUT (ปรับใหม่)
        // =========================
        new Thread(() -> {
            if (!serviceAlive.get()) return;
            SystemClock.sleep(3000); // 🔴 เพิ่มความเสถียร

            if (sessionId != currentConnectSession) return;

            if (connecting.get() && !connected.get()) {

                sendStatus("CONNECT_TIMEOUT");

                try {
                    finalSocket.close();
                    SystemClock.sleep(100); // 🔴 FIX native
                } catch (Throwable ignored) {}

                try { connectThread.interrupt(); } catch (Throwable ignored) {}

                connecting.set(false);
                connected.set(false);

                safeClose();

                reconnectFailCount++;

                if (reconnectFailCount >= 3) {

                    sendStatus("STOPPED");

                    new Thread(() -> {
                        SystemClock.sleep(300);
                        if (!isStopping.getAndSet(true)) {
                            try { stopSelf(); } catch (Exception ignored) {}
                        }
                    }).start();
                }
            }

        }, "BT-TIMEOUT").start();
    }

    // =========================
// 🔴 FUNCTION: handleConnected() (FINAL - SAFE)
// FILE: BluetoothService.java
// =========================
    private void handleConnected(BluetoothSocket sock) {

        // =========================
        // 🔴 กัน timeout / cancel ซ้อน
        // =========================
        if (!connecting.get()) {
            try { sock.close(); } catch (Throwable ignored) {}
            return;
        }

        // =========================
        // 🔴 STREAM (กัน crash เต็ม)
        // =========================
        try {

            socket = sock;

            if (socket == null || !socket.isConnected()) {
                sendStatus("SOCKET_INVALID");
                connecting.set(false);
                connected.set(false);
                safeClose();
                return;
            }

            input = socket.getInputStream();
            output = socket.getOutputStream();

            if (input == null || output == null) {
                sendStatus("STREAM_NULL");
                connecting.set(false);
                connected.set(false);
                safeClose();
                return;
            }

        } catch (Throwable e) {

            sendStatus("STREAM_FAIL");

            connecting.set(false);
            connected.set(false);

            safeClose();
            return;
        }

        // =========================
        // 🔴 HANDSHAKE (กันค้าง + กัน crash)
        // =========================
        try {

            boolean ok = false;

            for (int attempt = 0; attempt < 2; attempt++) {

                // 🔴 กัน cancel ระหว่าง handshake
                if (!connecting.get()) {
                    safeClose();
                    return;
                }

                output.write(new byte[]{0x55, (byte) 0xAA});
                output.flush();

                long start = System.currentTimeMillis();

                int r1 = -1;
                int r2 = -1;

                while (System.currentTimeMillis() - start < 1000) {

                    if (!connecting.get()) {
                        safeClose();
                        return;
                    }

                    try {

                        if (input.available() > 0) {
                            if (r1 == -1) r1 = input.read();
                            else {
                                r2 = input.read();
                                break;
                            }
                        }

                    } catch (Throwable ignored) {}

                    SystemClock.sleep(5);
                }

                if (r1 == -1 || r2 == -1) continue;

                if ((r1 & 0xFF) == 0xAA && (r2 & 0xFF) == 0x55) {
                    ok = true;
                    break;
                }
            }

            if (!ok) {
                sendStatus("HANDSHAKE_FAIL");
                connecting.set(false);
                connected.set(false);
                safeClose();
                return;
            }

        } catch (Throwable e) {

            sendStatus("HANDSHAKE_FAIL");

            connecting.set(false);
            connected.set(false);

            safeClose();
            return;
        }

        // =========================
        // 🔴 SUCCESS
        // =========================
        connected.set(true);
        connecting.set(false);

        lastRxTime = System.currentTimeMillis();
        lastSeq = -1;

        waitingAck = -1;
        retryCount = 0;

        sendStatus("CONNECTED");

        // =========================
        // 🔴 RX THREAD (กัน crash)
        // =========================
        rxThread = new Thread(() -> {

            try {

                while (connected.get()) {

                    if (input == null) break;

                    // 🔴 กัน blocking read ค้าง
                    if (input.available() > 0) {
                        rxLoop();
                    } else {
                        SystemClock.sleep(5);
                    }
                }

            } catch (Throwable t) {

                sendStatus("RX_CRASH");

            } finally {

                connected.set(false);
                connecting.set(false);
                safeClose();
            }

        }, "BT-RX");

        rxThread.start();
    }

    // =========================
    private void rxLoop() {

        byte[] buffer = new byte[32];

        while (running.get() && connected.get()) {

            try {

                // 🔴 FIX: กัน blocking read
                if (input == null || input.available() <= 0) {
                    SystemClock.sleep(5);
                    return;
                }

                int b = input.read();

                if (b == -1) return;
                if ((b & 0xFF) != 0xAA) return;

                // 🔴 กันค้างตรงนี้ด้วย
                if (input.available() <= 0) return;
                int len = input.read();

                if (len <= 0 || len > 24) return;

                int read = 0;
                while (read < len) {

                    if (input.available() <= 0) {
                        SystemClock.sleep(2);
                        continue;
                    }

                    int r = input.read(buffer, read, len - read);

                    if (r > 0) read += r;
                    else return;
                }

                // 🔴 CRC
                if (input.available() < 2) return;

                int crcLow = input.read();
                int crcHigh = input.read();
                int crcRx = (crcHigh << 8) | crcLow;

                int crc = 0xFFFF;
                crc = CRCUtil.crc16Update(crc, 0xAA);
                crc = CRCUtil.crc16Update(crc, len);

                for (int i = 0; i < len; i++) {
                    crc = CRCUtil.crc16Update(crc, buffer[i] & 0xFF);
                }

                if ((crc & 0xFFFF) != crcRx) return;

                lastRxTime = System.currentTimeMillis();

                int idx = 0;

                if (len < 2) return;

                int type = buffer[idx++] & 0xFF;
                int seq = buffer[idx++] & 0xFF;

                if (type == 0x01 && seq == lastSeq) return;
                lastSeq = seq;

                // 🔴 TELEMETRY
                if (type == 0x01) {

                    if (len < 18) return;

                    int flags = buffer[idx++] & 0xFF;
                    int error = buffer[idx++] & 0xFF;

                    float volt = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;

                    float m1 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;
                    float m2 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;
                    float m3 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;
                    float m4 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;

                    float tempL = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF)));
                    float tempR = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF)));

                    if (telemetryListener != null) {
                        try {
                            telemetryListener.onTelemetry(
                                    flags, error,
                                    volt, m1, m2, m3, m4,
                                    tempL, tempR
                            );
                        } catch (Throwable t) {
                            sendStatus("CALLBACK_ERROR");
                            telemetryListener = null;
                        }
                    }

                    return;
                }

                // 🔴 ACK
                if (type == 0x03) {

                    if (seq == waitingAck) {
                        waitingAck = -1;
                        retryCount = 0;
                        processQueue();
                    }

                    return;
                }

            } catch (Throwable t) {

                connected.set(false);
                connecting.set(false);

                sendStatus("RX_ERROR");

                safeClose();
                return;
            }
        }
    }

    // =========================
    public void queueCommand(byte cmd) {
        commandQueue.clear();
        commandQueue.add(cmd);
        processQueue();
    }

    public void sendPriorityCommand(byte cmd) {

        commandQueue.clear();
        waitingAck = -1;
        retryCount = 0;

        commandQueue.add(cmd);
        processQueue();
    }

    private void processQueue() {

        if (!connected.get()) return;
        if (waitingAck != -1) return;

        if (!processingQueue.compareAndSet(false, true)) return;

        try {

            Byte cmdObj = commandQueue.poll();
            if (cmdObj == null) return;

            byte cmd = cmdObj;

            sendCommandInternal(cmd, false);

        } finally {
            processingQueue.set(false);
        }
    }

    private void sendCommandInternal(byte cmd, boolean isRetry) {

        try {

            if (output == null || !connected.get()) return;

            if (!isRetry && waitingAck != -1) return;

            byte[] packet = new byte[16];
            int idx = 0;

            packet[idx++] = (byte) 0xAA;
            int lenIndex = idx++;

            packet[idx++] = 0x02;
            packet[idx++] = (byte) txSeq;
            packet[idx++] = cmd;

            packet[lenIndex] = (byte) (idx - 2);

            int crc = CRCUtil.crc16(packet, idx) & 0xFFFF;

            packet[idx++] = (byte) (crc & 0xFF);
            packet[idx++] = (byte) ((crc >> 8) & 0xFF);

            output.write(packet, 0, idx);
            output.flush();

            if (!isRetry) {
                waitingAck = txSeq;
                txSeq = (txSeq + 1) & 0xFF;
                retryCount = 0;
                lastCmdSent = cmd;
            }

            lastCmdTime = System.currentTimeMillis();

        } catch (Exception e) {

            sendStatus("TX_ERROR");

            connected.set(false);
            safeClose();
        }
    }

    private void sendStatus(String status) {

        // 🔴 กันยิงหลัง Service ตาย (สำคัญมาก)
        if (!serviceAlive.get()) return;

        // 🔴 กัน null
        if (status == null) return;

        // 🔴 กัน spam ซ้ำ
        if (status.equals(lastStatus)) return;

        lastStatus = status;

        try {

            Intent intent = new Intent("TNMOWER_STATUS");
            intent.putExtra("status", status);

            // 🔴 กัน context พัง
            if (getApplicationContext() == null) return;

            // 🔴 Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                sendBroadcast(intent, null);

            } else {

                sendBroadcast(intent);
            }

        } catch (Throwable ignored) {
            // 🔴 กัน crash ทุกกรณี
        }
    }

    private void safeClose() {

        try {
            if (input != null) {
                input.close();
                input = null;
            }
        } catch (Exception ignored) {}

        try {
            if (output != null) {
                output.close();
                output = null;
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception ignored) {}

        connected.set(false);
        connecting.set(false);
    }

    // =========================
// 🔴 FUNCTION: restartBluetoothAdapter()
// =========================
    private void restartBluetoothAdapter() {

        try {

            if (btAdapter == null) return;

            long now = System.currentTimeMillis();

            if (now - lastBtResetTime < BT_RESET_COOLDOWN) {
                return; // 🔴 กัน reset รัว
            }

            lastBtResetTime = now;

            sendStatus("BT_RESET");

            btAdapter.disable();
            SystemClock.sleep(1500);
            btAdapter.enable();

        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {

        serviceAlive.set(false); // 🔴 สำคัญ

        running.set(false);
        connected.set(false);
        connecting.set(false);

        try {
            if (rxThread != null) {
                rxThread.interrupt();
                rxThread = null;
            }
        } catch (Exception ignored) {}

        safeClose();

        sendStatus("DISCONNECTED");

        super.onDestroy();
    }

    // =========================
    // 🔴 TELEMETRY
    // =========================
    public interface OnTelemetryListener {
        void onTelemetry(int flags, int error,
                         float volt,
                         float m1, float m2, float m3, float m4,
                         float tempL, float tempR);
    }

    // =========================
    // 🔴 BINDER
    // =========================
    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }
}