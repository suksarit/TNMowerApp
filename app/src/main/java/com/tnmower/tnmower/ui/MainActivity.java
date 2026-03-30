package com.tnmower.tnmower.ui;

import android.content.*;
import android.content.SharedPreferences;
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

    private TextView txtVolt, txtStatus;
    private TextView txtTempL, txtTempR;
    private TextView txtM1, txtM2, txtM3, txtM4;

    private Button btnConnect, btnDisconnect, btnStop;

    private String selectedMAC = "";

    private boolean connected = false;
    private boolean connecting = false;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;

    private static final int RECONNECT_DELAY = 3000;
    private static final int CONNECT_TIMEOUT = 5000;

    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT = 5;

    private boolean isReceiverRegistered = false;

    private Vibrator vibrator;

    // =========================
    // 🔴 SMOOTH DATA
    // =========================
    private TelemetryData smoothData = null;
    private long lastUiUpdate = 0;
    private static final long UI_INTERVAL = 100;

    // =========================
    // 🔴 SERVICE
    // =========================
    private BluetoothService btService = null;
    private boolean isBound = false;

    // =========================
    // 🔴 Activity Result (แทนของเก่า)
    // =========================
    private final ActivityResultLauncher<Intent> deviceLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            String mac = result.getData().getStringExtra("MAC");
                            if (mac != null) {
                                selectedMAC = mac;
                                startBluetooth(mac);
                            }
                        }
                    });

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

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

        // 🔴 FIX: no unused view
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
            stopService(new Intent(this, BluetoothService.class));

            connected = false;
            connecting = false;

            txtStatus.setText(R.string.status_disconnected);
            txtStatus.setTextColor(Color.RED);

            updateButtonState();
        });

        btnStop.setOnClickListener(v -> sendStopCommand());

        BluetoothService.setTelemetryListener((volt, m1, m2, m3, m4, tL, tR) -> {

            TelemetryData raw = new TelemetryData(
                    volt, m1, m2, m3, m4, tL, tR
            );

            updateUI(raw);
        });

        if (!selectedMAC.isEmpty()) {
            startBluetooth(selectedMAC);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = new Intent(this, BluetoothService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void updateUI(TelemetryData raw) {

        long now = System.currentTimeMillis();

        if (now - lastUiUpdate < UI_INTERVAL) return;
        if (!raw.isValid()) return;

        if (smoothData == null) {
            smoothData = raw;
        }

        float alpha = 0.2f;

        smoothData.volt = smooth(raw.volt, smoothData.volt, alpha);
        smoothData.tempL = smooth(raw.tempL, smoothData.tempL, alpha);
        smoothData.tempR = smooth(raw.tempR, smoothData.tempR, alpha);

        smoothData.m1 = smooth(raw.m1, smoothData.m1, alpha);
        smoothData.m2 = smooth(raw.m2, smoothData.m2, alpha);
        smoothData.m3 = smooth(raw.m3, smoothData.m3, alpha);
        smoothData.m4 = smooth(raw.m4, smoothData.m4, alpha);

        lastUiUpdate = now;

        runOnUiThread(() -> {

            txtVolt.setText(String.format(Locale.US, "%.1f V", smoothData.volt));

            txtTempL.setText(String.format(Locale.US, "L: %.1f °C", smoothData.tempL));
            txtTempR.setText(String.format(Locale.US, "R: %.1f °C", smoothData.tempR));

            txtM1.setText(String.format(Locale.US, "M1: %.1f A", smoothData.m1));
            txtM2.setText(String.format(Locale.US, "M2: %.1f A", smoothData.m2));
            txtM3.setText(String.format(Locale.US, "M3: %.1f A", smoothData.m3));
            txtM4.setText(String.format(Locale.US, "M4: %.1f A", smoothData.m4));

            setColorSmart(txtVolt, smoothData.volt, 20, 24);

            setColorSmart(txtTempL, smoothData.tempL, 60, 80);
            setColorSmart(txtTempR, smoothData.tempR, 60, 80);

            setColorSmart(txtM1, smoothData.m1, 20, 30);
            setColorSmart(txtM2, smoothData.m2, 20, 30);
            setColorSmart(txtM3, smoothData.m3, 20, 30);
            setColorSmart(txtM4, smoothData.m4, 20, 30);

            if (raw.hasError()) {
                vibrateAlert();
            }
        });
    }

    private float smooth(float target, float current, float alpha) {
        return current + alpha * (target - current);
    }

    private void setColorSmart(TextView tv, float value, float warn, float danger) {
        if (value >= danger) tv.setTextColor(Color.RED);
        else if (value >= warn) tv.setTextColor(0xFFFFA500);
        else tv.setTextColor(Color.WHITE);
    }

    private void vibrateAlert() {
        if (vibrator == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(200);
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
        startService(intent);

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

            ContextCompat.registerReceiver(
                    this,
                    statusReceiver,
                    filter,
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