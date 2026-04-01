package com.tnmower.tnmower.ui;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tnmower.tnmower.R;
import com.tnmower.tnmower.bluetooth.BluetoothService;
import com.tnmower.tnmower.model.TelemetryData;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int MAX_RECONNECT = 5;
    private static final long UI_INTERVAL = 100;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    private TextView txtVolt, txtStatus;
    private Button btnConnect, btnDisconnect, btnStop;

    private String selectedMAC = "";

    private boolean connected = false;
    private boolean connecting = false;

    private Runnable reconnectRunnable;
    private int reconnectAttempts = 0;

    private TelemetryData smoothData = null;
    private long lastUiUpdate = 0;

    private BluetoothService btService = null;
    private boolean isBound = false;

    // =========================
    // SERVICE
    // =========================
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            btService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            btService = null;
        }
    };

    // =========================
    // STATUS RECEIVER
    // =========================
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String status = intent.getStringExtra("status");

            if ("CONNECTED".equals(status)) {
                connected = true;
                connecting = false;
                reconnectAttempts = 0;
                stopReconnect();
            }

            if ("DISCONNECTED".equals(status)) {
                connected = false;
                connecting = false;
                scheduleReconnect();
            }

            updateStatusText(status);
            updateButtonState();
        }
    };

    private boolean isReceiverRegistered = false;

    // =========================
    // DEVICE SELECT
    // =========================
    private final ActivityResultLauncher<Intent> deviceLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String mac = result.getData().getStringExtra("MAC");
                    if (mac != null) {

                        selectedMAC = mac;

                        // 🔴 save MAC
                        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
                        sp.edit().putString("MAC", mac).apply();

                        startBluetooth(mac);
                    }
                }
            });

    // =========================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestBluetoothPermission();

        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
        selectedMAC = sp.getString("MAC", "");

        txtVolt = findViewById(R.id.txtVolt);
        txtStatus = findViewById(R.id.txtStatus);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnStop = findViewById(R.id.btnStop);

        updateButtonState();

        // 🔴 AUTO CONNECT
        if (!selectedMAC.isEmpty()) {
            startBluetooth(selectedMAC);
        }

        btnConnect.setOnClickListener(v -> {
            if (connecting || connected) return;

            if (!hasPermission()) {
                Toast.makeText(this, "กรุณาอนุญาต Bluetooth ก่อน", Toast.LENGTH_SHORT).show();
                requestBluetoothPermission();
                return;
            }

            reconnectAttempts = 0;
            deviceLauncher.launch(new Intent(this, DeviceListActivity.class));
        });

        btnDisconnect.setOnClickListener(v -> {

            stopReconnect();

            if (btService != null) {
                btService.sendStop();
            }

            stopService(new Intent(this, BluetoothService.class));

            connected = false;
            connecting = false;

            updateButtonState();

            txtStatus.setText("DISCONNECTED");
            txtStatus.setTextColor(Color.RED);
        });

        btnStop.setOnClickListener(v -> sendStopCommand());

        BluetoothService.setTelemetryListener((flags, error, volt, m1, m2, m3, m4, tL, tR) -> {

            TelemetryData raw = new TelemetryData(flags, error, volt, m1, m2, m3, m4, tL, tR);
            updateUI(raw);
        });
    }

    // =========================
    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
            }, 1001);
        }
    }

    // =========================
    private void startBluetooth(String mac) {

        if (connecting) return;

        // 🔴 fallback ถ้า MAC หาย
        if (mac == null || mac.isEmpty()) {
            deviceLauncher.launch(new Intent(this, DeviceListActivity.class));
            return;
        }

        connecting = true;
        connected = false;

        txtStatus.setText("CONNECTING...");
        txtStatus.setTextColor(Color.YELLOW);

        updateButtonState();

        Intent intent = new Intent(this, BluetoothService.class);
        intent.putExtra("MAC", mac);

        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            if (!connected) {

                connecting = false;

                txtStatus.setText("CONNECT FAIL");
                txtStatus.setTextColor(Color.RED);

                scheduleReconnect();
                updateButtonState();
            }

        }, CONNECT_TIMEOUT);
    }

    private void scheduleReconnect() {

        if (selectedMAC == null || selectedMAC.isEmpty()) {
            deviceLauncher.launch(new Intent(this, DeviceListActivity.class));
            return;
        }

        if (reconnectAttempts >= MAX_RECONNECT) {
            txtStatus.setText("RECONNECT LIMIT");
            txtStatus.setTextColor(Color.RED);
            return;
        }

        reconnectAttempts++;

        int delay;
        if (reconnectAttempts < 3) delay = 2000;
        else if (reconnectAttempts < 5) delay = 4000;
        else delay = 8000;

        reconnectRunnable = () -> {
            if (!connected && !connecting) {
                startBluetooth(selectedMAC);
            }
        };

        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    private void stopReconnect() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void sendStopCommand() {
        if (btService != null && isBound) {
            btService.sendStop();
        }
    }

    private void updateUI(TelemetryData raw) {

        long now = System.currentTimeMillis();
        if (now - lastUiUpdate < UI_INTERVAL) return;
        if (!raw.isValid()) return;

        if (smoothData == null) smoothData = raw;

        smoothData.volt = smooth(raw.volt, smoothData.volt, 0.1f);

        lastUiUpdate = now;

        runOnUiThread(() ->
                txtVolt.setText(String.format(Locale.US, "%.1f V", smoothData.volt))
        );
    }

    private float smooth(float target, float current, float alpha) {
        return current + alpha * (target - current);
    }

    private void updateButtonState() {
        btnConnect.setEnabled(!connected && !connecting);
        btnDisconnect.setEnabled(connected);
    }

    private void updateStatusText(String status) {
        txtStatus.setText(status);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!isReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    statusReceiver,
                    new IntentFilter("TNMOWER_STATUS"),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            isReceiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (isReceiverRegistered) {
            unregisterReceiver(statusReceiver);
            isReceiverRegistered = false;
        }
    }
}