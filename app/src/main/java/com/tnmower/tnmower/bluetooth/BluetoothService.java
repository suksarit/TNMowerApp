package com.tnmower.tnmower.bluetooth;

import android.app.*;
import android.bluetooth.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.*;

import androidx.core.app.NotificationCompat;

import com.tnmower.tnmower.utils.CRCUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothService extends Service {

    private static final String CHANNEL_ID = "TN_MOWER_BT";

    private BluetoothAdapter btAdapter;
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;

    private final String MAC = "00:21:13:00:00:00";

    private final UUID UUID_SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private final LinkedBlockingQueue<String> txQueue = new LinkedBlockingQueue<>();

    private int seq = 0;

    // =========================
    // STATUS TRACK
    // =========================
    private String lastStatus = "";

    // =========================
    // RECONNECT
    // =========================
    private long lastConnectAttempt = 0;
    private int reconnectDelay = 2000;

    // =========================
    // TIMEOUT
    // =========================
    private long lastRxTime = 0;
    private static final long RX_TIMEOUT = 3000;

    // =========================
    // LISTENER
    // =========================
    public interface OnTelemetryListener {
        void onTelemetry(float volt, float current, float temp);
    }

    private static OnTelemetryListener telemetryListener;

    public static void setTelemetryListener(OnTelemetryListener listener) {
        telemetryListener = listener;
    }

    // ==================================================
    @Override
    public void onCreate() {
        super.onCreate();

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        startForegroundService();

        new Thread(this::connectionLoop).start();
        new Thread(this::txLoop).start();
    }

    // ==================================================
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && intent.hasExtra("cmd")) {

            String cmd = intent.getStringExtra("cmd");

            if ("STOP".equals(cmd)) {
                sendPacket("CMD", "STOP");
            }
        }

        return START_STICKY;
    }

    // ==================================================
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

    // ==================================================
    private void connectionLoop() {

        while (running.get()) {

            long now = System.currentTimeMillis();

            // 🔴 TIMEOUT → ตัดจริง
            if (connected.get() && (now - lastRxTime > RX_TIMEOUT)) {
                sendStatus("TIMEOUT");
                connected.set(false);
                safeClose();
            }

            // 🔴 CONNECT / RECONNECT
            if (!connected.get()) {

                if (now - lastConnectAttempt > reconnectDelay) {

                    lastConnectAttempt = now;

                    connect();

                    reconnectDelay = Math.min(reconnectDelay + 1000, 8000);
                }

            } else {
                reconnectDelay = 2000;
            }

            SystemClock.sleep(300);
        }
    }

    // ==================================================
    private void connect() {

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {

                    sendStatus("NO_PERMISSION");
                    return;
                }
            }

            sendStatus("CONNECTING");

            btAdapter.cancelDiscovery();

            BluetoothDevice device = btAdapter.getRemoteDevice(MAC);

            socket = device.createRfcommSocketToServiceRecord(UUID_SPP);
            socket.connect();

            input = socket.getInputStream();
            output = socket.getOutputStream();

            connected.set(true);
            lastRxTime = System.currentTimeMillis();

            sendStatus("CONNECTED");

            new Thread(this::rxLoop).start();

        } catch (Exception e) {

            connected.set(false);
            sendStatus("RECONNECTING");
            safeClose();
        }
    }

    // ==================================================
    private void rxLoop() {

        StringBuilder buffer = new StringBuilder();

        while (running.get() && connected.get()) {

            try {

                int c = input.read();

                if (c == -1) continue;

                buffer.append((char) c);

                if (c == '>') {

                    String packet = buffer.toString();
                    buffer.setLength(0);

                    lastRxTime = System.currentTimeMillis();

                    handlePacket(packet);
                }

                if (buffer.length() > 200) buffer.setLength(0);

            } catch (Exception e) {

                connected.set(false);
                sendStatus("RECONNECTING");
                safeClose();
                break;
            }
        }
    }

    // ==================================================
    private void handlePacket(String packet) {

        if (!packet.startsWith("<") || !packet.endsWith(">")) return;

        try {

            packet = packet.substring(1, packet.length() - 1);

            String[] parts = packet.split(",");

            if (parts.length < 4) return;

            String raw = parts[0] + "," + parts[1] + "," + parts[2];

            if (!CRCUtil.calcCRC(raw).equals(parts[3])) return;

            String type = parts[1];
            String data = parts[2];

            if (type.equals("TEL")) {

                String[] d = data.split(",");

                float volt = Float.parseFloat(d[0].split(":")[1]);
                float current = Float.parseFloat(d[1].split(":")[1]);
                float temp = Float.parseFloat(d[2].split(":")[1]);

                if (telemetryListener != null) {
                    telemetryListener.onTelemetry(volt, current, temp);
                }
            }

        } catch (Exception ignored) {}
    }

    // ==================================================
    private void txLoop() {

        while (running.get()) {

            try {

                String msg = txQueue.take();

                if (connected.get() && output != null) {
                    output.write(msg.getBytes());
                    output.flush();
                }

            } catch (Exception ignored) {}
        }
    }

    // ==================================================
    private void sendPacket(String type, String data) {

        seq++;

        String raw = seq + "," + type + "," + data;
        String crc = CRCUtil.calcCRC(raw);

        String packet = "<" + raw + "," + crc + ">";
        txQueue.offer(packet);
    }

    // ==================================================
    // 🔴 FIX: กันยิง status ซ้ำ
    private void sendStatus(String status) {

        if (status.equals(lastStatus)) return;

        lastStatus = status;

        Intent intent = new Intent("TNMOWER_STATUS");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    // ==================================================
    private void safeClose() {
        try { if (input != null) input.close(); } catch (Exception ignored) {}
        try { if (output != null) output.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    // ==================================================
    @Override
    public void onDestroy() {
        running.set(false);
        connected.set(false);
        safeClose();
        sendStatus("DISCONNECTED");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
