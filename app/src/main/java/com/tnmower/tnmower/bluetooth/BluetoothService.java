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

public class BluetoothService extends Service {

    private static final String CHANNEL_ID = "TN_MOWER_BT";
    private static final long RX_TIMEOUT = 3000;
    private static final long RESEND_INTERVAL = 300;
    private static final int MAX_RETRY = 3;
    private static OnTelemetryListener telemetryListener;
    private final UUID UUID_SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
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
    private long lastCmdTime = 0;
    private int retryCount = 0;
    private byte lastCmdSent = 0;

    private Thread rxThread = null;

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
        if (running.compareAndSet(false, true)) {
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

        while (running.get()) {

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
                if (!connected.get() && !connecting.get()) {

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
// 🔴 FUNCTION: connect()
// FILE: BluetoothService.java
// หน้าที่: เชื่อมต่อแบบกัน crash + แยกสถานะชัดเจน
// =========================
    private synchronized void connect() {

        // =========================
        // 🔴 กัน connect ซ้อน (production)
        // =========================
        if (connected.get() || connecting.get()) {
            return;
        }

        connecting.set(true);

        // 🔴 reset state
        connected.set(false);
        waitingAck = -1;
        retryCount = 0;

        // 🔴 ปิดของเก่าทั้งหมด
        safeClose();

        // 🔴 kill thread เก่า (สำคัญมาก)
        try {
            if (rxThread != null && rxThread.isAlive()) {
                rxThread.interrupt();
                rxThread = null;
            }
        } catch (Exception ignored) {}

        try {

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

            if (btAdapter == null) {
                sendStatus("NO_BT");
                connecting.set(false);
                return;
            }

            btAdapter.cancelDiscovery();

            // =========================
            // 🔴 DEVICE
            // =========================
            BluetoothDevice device;
            try {
                device = btAdapter.getRemoteDevice(MAC);
            } catch (Throwable t) {
                sendStatus("INVALID_MAC");
                connecting.set(false);
                return;
            }

            // =========================
            // 🔴 SOCKET
            // =========================
            try {
                socket = device.createRfcommSocketToServiceRecord(UUID_SPP);
            } catch (Throwable t) {
                sendStatus("SOCKET_FAIL");
                connecting.set(false);
                return;
            }

            // =========================
            // 🔴 CONNECT
            // =========================
            try {
                socket.connect();
            } catch (IOException e) {
                sendStatus("CONNECT_FAIL");
                connecting.set(false);
                safeClose();
                return;
            } catch (Throwable t) {
                sendStatus("CONNECT_FATAL");
                connecting.set(false);
                safeClose();
                return;
            }

            // =========================
            // 🔴 STREAM
            // =========================
            try {
                input = socket.getInputStream();
                output = socket.getOutputStream();
            } catch (Throwable t) {
                sendStatus("STREAM_FAIL");
                connecting.set(false);
                safeClose();
                return;
            }

            // =========================
            // 🔴 HANDSHAKE
            // =========================
            try {

                boolean ok = false;

                for (int attempt = 0; attempt < 2; attempt++) {

                    output.write(new byte[]{0x55, (byte) 0xAA});
                    output.flush();

                    long start = System.currentTimeMillis();

                    int r1 = -1;
                    int r2 = -1;

                    while (System.currentTimeMillis() - start < 1000) {

                        try {

                            if (r1 == -1 && input.available() > 0) {
                                r1 = input.read();
                            }

                            if (r1 != -1 && input.available() > 0) {
                                r2 = input.read();
                                break;
                            }

                        } catch (Exception ignored) {}

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
                    safeClose();
                    return;
                }

            } catch (Throwable t) {
                sendStatus("HANDSHAKE_FAIL");
                connecting.set(false);
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
            // 🔴 RX THREAD (FIX ตัวจริง)
            // =========================
            rxThread = new Thread(() -> {
                try {
                    rxLoop();
                } catch (Throwable t) {
                    sendStatus("RX_CRASH");
                    connected.set(false);
                    safeClose();
                }
            }, "BT-RX");

            rxThread.start();

        } catch (Throwable t) {

            connected.set(false);
            connecting.set(false);

            sendStatus("FATAL_ERROR");

            safeClose();
        }
    }

    // =========================
    private void rxLoop() {

        byte[] buffer = new byte[32];

        while (running.get() && connected.get()) {

            try {

                // =========================
                // 🔴 READ HEADER
                // =========================
                int b;
                try {
                    b = input.read();
                } catch (Throwable t) {
                    throw new RuntimeException("READ_FAIL");
                }

                if (b == -1) continue;
                if ((b & 0xFF) != 0xAA) continue;

                int len;
                try {
                    len = input.read();
                } catch (Throwable t) {
                    throw new RuntimeException("LEN_FAIL");
                }

                if (len <= 0 || len > 24) continue;

                // =========================
                // 🔴 READ PAYLOAD
                // =========================
                int read = 0;
                while (read < len) {
                    int r = input.read(buffer, read, len - read);
                    if (r > 0) {
                        read += r;
                    } else {
                        throw new RuntimeException("PAYLOAD_FAIL");
                    }
                }

                // =========================
                // 🔴 CRC
                // =========================
                int crcLow = input.read();
                int crcHigh = input.read();
                int crcRx = (crcHigh << 8) | crcLow;

                int crc = 0xFFFF;
                crc = CRCUtil.crc16Update(crc, 0xAA);
                crc = CRCUtil.crc16Update(crc, len);

                for (int i = 0; i < len; i++) {
                    crc = CRCUtil.crc16Update(crc, buffer[i] & 0xFF);
                }

                if ((crc & 0xFFFF) != crcRx) continue;

                lastRxTime = System.currentTimeMillis();

                int idx = 0;

                // =========================
                // 🔴 SAFE READ (กัน index พัง)
                // =========================
                if (len < 2) continue;

                int type = buffer[idx++] & 0xFF;
                int seq = buffer[idx++] & 0xFF;

                if (type == 0x01 && seq == lastSeq) continue;
                lastSeq = seq;

                // =========================
                // 🔴 TELEMETRY
                // =========================
                if (type == 0x01) {

                    if (len < 18) continue; // 🔴 กัน data พัง

                    int flags = buffer[idx++] & 0xFF;
                    int error = buffer[idx++] & 0xFF;

                    float volt = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;

                    float m1 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;
                    float m2 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;
                    float m3 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;
                    float m4 = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF))) / 100f;

                    float tempL = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF)));
                    float tempR = ((short) ((buffer[idx++] << 8) | (buffer[idx++] & 0xFF)));

                    // =========================
// 🔴 SAFE CALLBACK (กัน crash UI)
// =========================
                    if (telemetryListener != null) {
                        try {

                            telemetryListener.onTelemetry(
                                    flags,
                                    error,
                                    volt,
                                    m1,
                                    m2,
                                    m3,
                                    m4,
                                    tempL,
                                    tempR
                            );

                        } catch (Throwable t) {

                            // 🔴 กัน crash ทั้งแอพ
                            sendStatus("CALLBACK_ERROR");

                            // ❗ สำคัญ: ตัด listener ทิ้งทันที
                            telemetryListener = null;
                        }
                    }
                    continue;
                }

                // =========================
                // 🔴 ACK
                // =========================
                if (type == 0x03) {

                    if (seq == waitingAck) {
                        waitingAck = -1;
                        retryCount = 0;
                        processQueue();
                    }

                    continue;
                }

            } catch (Throwable t) {

                // 🔴 กัน thread ตาย (สำคัญมาก)
                connected.set(false);
                sendStatus("RX_ERROR");
                safeClose();
                break;
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

        if (status.equals(lastStatus)) return;

        lastStatus = status;

        Intent intent = new Intent("TNMOWER_STATUS");
        intent.putExtra("status", status);
        sendBroadcast(intent);
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
    }

    @Override
    public void onDestroy() {
        running.set(false);
        connected.set(false);
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