package com.tnmower.tnmower;

import android.app.*;
import android.bluetooth.*;
import android.content.Intent;
import android.os.*;
import androidx.core.app.NotificationCompat;

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
    private void startForegroundService() {

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "TN Mower BT",
                NotificationManager.IMPORTANCE_LOW);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);

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

            if (!connected.get()) {
                connect();
            }

            SystemClock.sleep(2000);
        }
    }

    // ==================================================
    private void connect() {

        try {

            btAdapter.cancelDiscovery();

            BluetoothDevice device = btAdapter.getRemoteDevice(MAC);

            socket = device.createRfcommSocketToServiceRecord(UUID_SPP);
            socket.connect();

            input = socket.getInputStream();
            output = socket.getOutputStream();

            connected.set(true);

            new Thread(this::rxLoop).start();

        } catch (Exception e) {
            connected.set(false);
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
                    handlePacket(buffer.toString());
                    buffer.setLength(0);
                }

            } catch (Exception e) {
                connected.set(false);
                safeClose();
                break;
            }
        }
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
    private void handlePacket(String p) {
        // 🔴 TODO: broadcast ไป UI
    }

    // ==================================================
    public void send(String msg) {
        txQueue.offer(msg);
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}