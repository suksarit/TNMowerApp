package com.tnmower.tnmower.ui;

import android.content.*;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.*;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tnmower.tnmower.R;
import com.tnmower.tnmower.bluetooth.BluetoothService;
import com.tnmower.tnmower.model.TelemetryData;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int RECONNECT_DELAY = 3000;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int MAX_RECONNECT = 5;
    private static final long UI_INTERVAL = 100;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    private TextView txtVolt, txtStatus;
    private TextView txtTempL, txtTempR;
    private TextView txtM1, txtM2, txtM3, txtM4;

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

    private final ActivityResultLauncher<Intent> deviceLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String mac = result.getData().getStringExtra("MAC");
                    if (mac != null) {
                        selectedMAC = mac;
                        startBluetooth(mac);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
        selectedMAC = sp.getString("MAC", "");

        txtVolt = findViewById(R.id.txtVolt);
        txtStatus = findViewById(R.id.txtStatus);

        txtTempL = findViewById(R.id.txtTempL);
        txtTempR = findViewById(R.id.txtTempR);

        txtM1 = findViewById(R.id.txtM1);
        txtM2 = findViewById(R.id.txtM2);
        txtM3 = findViewById(R.id.txtM3);
        txtM4 = findViewById(R.id.txtM4);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnStop = findViewById(R.id.btnStop);

        updateButtonState();

        btnConnect.setOnClickListener(v -> {
            if (connecting || connected) return;

            reconnectAttempts = 0;

            if (selectedMAC.isEmpty()) {
                deviceLauncher.launch(new Intent(this, DeviceListActivity.class));
            } else {
                startBluetooth(selectedMAC);
            }
        });

        btnDisconnect.setOnClickListener(v -> {

            stopReconnect();

            if (btService != null) {
                btService.sendStop(); // 🔴 สำคัญ
            }

            stopService(new Intent(this, BluetoothService.class));

            connected = false;
            connecting = false;

            updateButtonState();

            txtStatus.setText(R.string.status_disconnected);
            txtStatus.setTextColor(Color.RED);
        });

        btnStop.setOnClickListener(v -> sendStopCommand());

        BluetoothService.setTelemetryListener((volt, m1, m2, m3, m4, tL, tR) -> {
            TelemetryData raw = new TelemetryData(volt, m1, m2, m3, m4, tL, tR);
            updateUI(raw);
        });

        if (!selectedMAC.isEmpty()) {
            startBluetooth(selectedMAC);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 🔴 ไม่ bind ซ้ำ
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void startBluetooth(String mac) {

        if (connecting) return;

        connecting = true;
        connected = false;

        txtStatus.setText(R.string.status_connecting);
        txtStatus.setTextColor(Color.YELLOW);

        updateButtonState();

        Intent intent = new Intent(this, BluetoothService.class);
        intent.putExtra("MAC", mac);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!connected) {
                connecting = false;

                txtStatus.setText(R.string.status_failed);
                txtStatus.setTextColor(Color.RED);

                scheduleReconnect();
                updateButtonState();
            }
        }, CONNECT_TIMEOUT);
    }

    private void scheduleReconnect() {

        if (reconnectAttempts >= MAX_RECONNECT) return;

        reconnectAttempts++;

        reconnectRunnable = () -> {
            if (!connected && !connecting) {
                startBluetooth(selectedMAC);
            }
        };

        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY);
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

        if (smoothData == null) {
            smoothData = raw;
        }

        float alphaVolt = 0.1f;
        float alphaTemp = 0.15f;
        float alphaCurrent = 0.3f;

        smoothData.volt = smooth(raw.volt, smoothData.volt, alphaVolt);

        smoothData.tempL = smooth(raw.tempL, smoothData.tempL, alphaTemp);
        smoothData.tempR = smooth(raw.tempR, smoothData.tempR, alphaTemp);

        smoothData.m1 = smooth(raw.m1, smoothData.m1, alphaCurrent);
        smoothData.m2 = smooth(raw.m2, smoothData.m2, alphaCurrent);
        smoothData.m3 = smooth(raw.m3, smoothData.m3, alphaCurrent);
        smoothData.m4 = smooth(raw.m4, smoothData.m4, alphaCurrent);

        lastUiUpdate = now;

        runOnUiThread(() -> {

            txtVolt.setText(String.format(Locale.US, "%.1f V", smoothData.volt));

            txtTempL.setText(String.format(Locale.US, "L: %.1f °C", smoothData.tempL));
            txtTempR.setText(String.format(Locale.US, "R: %.1f °C", smoothData.tempR));

            txtM1.setText(String.format(Locale.US, "M1: %.1f A", smoothData.m1));
            txtM2.setText(String.format(Locale.US, "M2: %.1f A", smoothData.m2));
            txtM3.setText(String.format(Locale.US, "M3: %.1f A", smoothData.m3));
            txtM4.setText(String.format(Locale.US, "M4: %.1f A", smoothData.m4));
        });
    }

    private float smooth(float target, float current, float alpha) {
        return current + alpha * (target - current);
    }

    private void updateButtonState() {
        btnConnect.setEnabled(!connected && !connecting);
        btnDisconnect.setEnabled(connected);
    }

    private void updateStatusText(String status) {
        txtStatus.setText(getString(R.string.status_prefix, status));
        txtStatus.setTextColor(status.contains("CONNECTED") ? Color.GREEN : Color.RED);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter("TNMOWER_STATUS");
            ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
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