package com.tnmower.tnmower;

import android.Manifest;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    // ==================================================
    // BLUETOOTH CORE
    // ==================================================
    private BluetoothAdapter btAdapter;
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;

    private final String MAC = "00:21:13:00:00:00";
    private final UUID UUID_SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // ==================================================
    // UI
    // ==================================================
    private TextView txtStatus, txtVolt, txtCurrent, txtTemp;

    // ==================================================
    // STATE CONTROL
    // ==================================================
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private int seq = 0;

    // ==================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        txtVolt = findViewById(R.id.txtVolt);
        txtCurrent = findViewById(R.id.txtCurrent);
        txtTemp = findViewById(R.id.txtTemp);

        Button btnConnect = findViewById(R.id.btnConnect);
        Button btnStop = findViewById(R.id.btnStop);

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        // Permission Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 1);
        }

        btnConnect.setOnClickListener(v -> connectBT());
        btnStop.setOnClickListener(v -> sendPacket("CMD", "STOP"));

        startHeartbeat();
    }

    // ==================================================
    // CONNECT (SAFE)
    // ==================================================
    private void connectBT() {

        new Thread(() -> {

            try {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread(() -> txtStatus.setText("No Permission"));
                        return;
                    }
                }

                btAdapter.cancelDiscovery();   // 🔴 สำคัญ

                BluetoothDevice device = btAdapter.getRemoteDevice(MAC);

                socket = device.createRfcommSocketToServiceRecord(UUID_SPP);
                socket.connect();

                input = socket.getInputStream();
                output = socket.getOutputStream();

                connected.set(true);

                runOnUiThread(() -> txtStatus.setText("Connected"));

                startReadLoop();

            } catch (Exception e) {

                connected.set(false);
                runOnUiThread(() -> txtStatus.setText("Connect Failed"));

                safeClose();
                autoReconnect();
            }

        }).start();
    }

    // ==================================================
    // READ LOOP (NON-BLOCK SAFE)
    // ==================================================
    private void startReadLoop() {

        new Thread(() -> {

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

                    runOnUiThread(() -> txtStatus.setText("Disconnected"));

                    safeClose();

                    if (running.get()) autoReconnect();
                    break;
                }
            }

        }).start();
    }

    // ==================================================
    // PACKET PARSE
    // ==================================================
    private void handlePacket(String packet) {

        if (!packet.startsWith("<") || !packet.endsWith(">")) return;

        try {

            packet = packet.substring(1, packet.length() - 1);

            String[] parts = packet.split(",");

            if (parts.length < 4) return;

            String raw = parts[0] + "," + parts[1] + "," + parts[2];

            if (!calcCRC(raw).equals(parts[3])) return;

            String type = parts[1];
            String data = parts[2];

            if (type.equals("TEL")) {

                String[] d = data.split(",");

                String v = d[0].split(":")[1];
                String i = d[1].split(":")[1];
                String t = d[2].split(":")[1];

                runOnUiThread(() -> {
                    txtVolt.setText("Volt: " + v);
                    txtCurrent.setText("Current: " + i);
                    txtTemp.setText("Temp: " + t);
                });
            }

        } catch (Exception ignored) {}
    }

    // ==================================================
    // SEND
    // ==================================================
    private synchronized void sendPacket(String type, String data) {

        if (!connected.get()) return;

        seq++;

        String raw = seq + "," + type + "," + data;
        String crc = calcCRC(raw);

        sendRaw("<" + raw + "," + crc + ">");
    }

    private void sendRaw(String msg) {
        try {
            if (output != null && connected.get()) {
                output.write(msg.getBytes());
                output.flush();
            }
        } catch (Exception ignored) {}
    }

    // ==================================================
    // HEARTBEAT
    // ==================================================
    private void startHeartbeat() {

        new Thread(() -> {

            while (running.get()) {

                try {
                    Thread.sleep(1000);

                    if (connected.get()) {
                        sendPacket("HB", "OK");
                    }

                } catch (Exception ignored) {}
            }

        }).start();
    }

    // ==================================================
    // AUTO RECONNECT (NO STACK)
    // ==================================================
    private void autoReconnect() {

        if (reconnecting.get()) return;

        reconnecting.set(true);

        new Thread(() -> {

            try {
                Thread.sleep(3000);

                if (running.get() && !connected.get()) {
                    connectBT();
                }

            } catch (Exception ignored) {}

            reconnecting.set(false);

        }).start();
    }

    // ==================================================
    // CRC
    // ==================================================
    private String calcCRC(String data) {

        int crc = 0;

        for (int i = 0; i < data.length(); i++) {
            crc ^= data.charAt(i);
        }

        return String.format("%02X", crc);
    }

    // ==================================================
    // SAFE CLOSE
    // ==================================================
    private void safeClose() {

        try {
            if (input != null) input.close();
        } catch (Exception ignored) {}

        try {
            if (output != null) output.close();
        } catch (Exception ignored) {}

        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }

    // ==================================================
    // DESTROY
    // ==================================================
    @Override
    protected void onDestroy() {
        super.onDestroy();

        running.set(false);
        connected.set(false);

        safeClose();
    }
}

