package com.tnmower.tnmower.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tnmower.tnmower.R;
import com.tnmower.tnmower.bluetooth.BluetoothService;
import com.tnmower.tnmower.model.TelemetryData;

public class MainActivity extends AppCompatActivity {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final long UI_INTERVAL = 100;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    // UI
    private TextView txtVolt, txtStatus;
    private TextView txtTempL, txtTempR;
    private TextView txtM1, txtM2, txtM3, txtM4;

    private Button btnConnect, btnDisconnect, btnStop;

    private String selectedMAC = "";

    private boolean connected = false;
    private boolean connecting = false;

    private Runnable reconnectRunnable;
    // =========================
// 🔴 STATUS RECEIVER (SAFE)
// =========================
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String status = intent.getStringExtra("status");
            if (status == null) return;

            // 🔴 บังคับให้ทำงานบน UI Thread เท่านั้น
            MainActivity.this.runOnUiThread(() -> {

                if ("CONNECTED".equals(status)) {

                    connected = true;
                    connecting = false;

                    stopReconnect();

                    txtStatus.setText("CONNECTED");
                    txtStatus.setTextColor(Color.GREEN);

                } else if ("DISCONNECTED".equals(status)) {

                    connected = false;
                    connecting = false;

                    stopReconnect();

                    txtStatus.setText("DISCONNECTED");
                    txtStatus.setTextColor(Color.RED);

                } else {

                    txtStatus.setText(status);
                }

                updateButtonState();
            });
        }
    };

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

            connected = false;
            connecting = false;

            txtStatus.setText("SERVICE LOST");
            txtStatus.setTextColor(Color.RED);

            updateButtonState();
        }
    };
    // DEVICE SELECT
    // =========================
    private final ActivityResultLauncher<Intent> deviceLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String mac = result.getData().getStringExtra("MAC");
                    if (mac != null) {

                        selectedMAC = mac;

                        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
                        sp.edit().putString("MAC", mac).apply();

                        startBluetooth(mac);
                    }
                }
            });

    // =========================
    // onCreate
    // =========================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // =========================
// 🔴 Android 13+ Notification Permission
// =========================
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{
                        android.Manifest.permission.POST_NOTIFICATIONS
                }, 2001);
            }
        }

        requestBluetoothPermission();

        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
        selectedMAC = sp.getString("MAC", "");

        // bind UI
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

        // 🔴 หน่วง start Bluetooth กัน crash ตอนเปิด
        // 🔴 ปิด auto connect กัน crash loop
        if (!selectedMAC.isEmpty()) {

            txtStatus.setText("READY (PRESS CONNECT)");
            txtStatus.setTextColor(Color.GRAY);

            // ❗ รอ user กด connect เอง
        }

        btnConnect.setOnClickListener(v -> {
            if (connecting || connected) return;

            if (!hasPermission()) {

                requestBluetoothPermission();

                Toast.makeText(this, "รออนุญาต Bluetooth ก่อน", Toast.LENGTH_SHORT).show();

                return;   // 🔴 ห้ามเปิด DeviceList ทันที
            }

// 🔴 เปิดเฉพาะตอนมี permission แล้ว
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

            txtStatus.setText(getString(R.string.status_disconnected));
            txtStatus.setTextColor(Color.RED);
        });

        btnStop.setOnClickListener(v -> sendStopCommand());
    }

    // =========================
    // 🔴 สำคัญ: listener ต้องอยู่ใน onStart
    // =========================

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            if (isBound) {
                unbindService(serviceConnection);
                isBound = false;
            }
        } catch (Exception ignored) {
        }

        try {
            stopService(new Intent(this, BluetoothService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        BluetoothService.setTelemetryListener((flags, error, volt, m1, m2, m3, m4, tL, tR) -> {

            try {
                TelemetryData raw = new TelemetryData(
                        flags, error,
                        volt, m1, m2, m3, m4,
                        tL, tR
                );

                updateUI(raw);

            } catch (Throwable ignored) {
            }
        });
    }    // =========================

    // =========================
    // 🔴 ตัด listener ตอนออก
    // =========================
    @Override
    protected void onStop() {
        super.onStop();
        BluetoothService.setTelemetryListener(null);
    }

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

    private void startBluetooth(String mac) {

        // 🔴 HARD RESET
        try {
            if (isBound) {
                unbindService(serviceConnection);
                isBound = false;
            }
        } catch (Exception ignored) {
        }

        try {
            stopService(new Intent(this, BluetoothService.class));
        } catch (Exception ignored) {
        }

        // 🔴 VALIDATE
        if (mac == null || mac.length() < 17) {
            txtStatus.setText("INVALID MAC");
            txtStatus.setTextColor(Color.RED);
            return;
        }

        if (connecting) return;

        connecting = true;
        connected = false;

        txtStatus.setText(getString(R.string.status_connecting));
        txtStatus.setTextColor(Color.YELLOW);

        updateButtonState();

        Intent intent = new Intent(this, BluetoothService.class);
        intent.putExtra("MAC", mac);

        try {
            startService(intent);
            boolean ok = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

            if (!ok) {
                throw new Exception("Bind failed");
            }

        } catch (Exception e) {

            connecting = false;
            connected = false;

            txtStatus.setText("START FAIL");
            txtStatus.setTextColor(Color.RED);

            updateButtonState();
            return;
        }

        // 🔴 TIMEOUT
        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            if (isFinishing() || isDestroyed()) return;
            if (!connecting) return;

            if (!connected) {

                connecting = false;

                txtStatus.setText(getString(R.string.status_failed));
                txtStatus.setTextColor(Color.RED);

                updateButtonState();

                // 🔴 เพิ่มตรงนี้: กลับไปเลือกใหม่
                deviceLauncher.launch(new Intent(this, DeviceListActivity.class));
            }

        }, CONNECT_TIMEOUT);
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

        smoothData.m1 = smooth(raw.m1, smoothData.m1, 0.2f);
        smoothData.m2 = smooth(raw.m2, smoothData.m2, 0.2f);
        smoothData.m3 = smooth(raw.m3, smoothData.m3, 0.2f);
        smoothData.m4 = smooth(raw.m4, smoothData.m4, 0.2f);

        smoothData.tempL = smooth(raw.tempL, smoothData.tempL, 0.1f);
        smoothData.tempR = smooth(raw.tempR, smoothData.tempR, 0.1f);

        lastUiUpdate = now;

        runOnUiThread(() -> {

            txtVolt.setText(getString(R.string.format_voltage, smoothData.volt));

            txtTempL.setText(getString(R.string.format_temp_l, smoothData.tempL));
            txtTempR.setText(getString(R.string.format_temp_r, smoothData.tempR));

            txtM1.setText(getString(R.string.format_m1, smoothData.m1));
            txtM2.setText(getString(R.string.format_m2, smoothData.m2));
            txtM3.setText(getString(R.string.format_m3, smoothData.m3));
            txtM4.setText(getString(R.string.format_m4, smoothData.m4));

            txtStatus.setText("RUNNING");
        });
    }

    private float smooth(float target, float current, float alpha) {
        return current + alpha * (target - current);
    }

    private void updateButtonState() {
        btnConnect.setEnabled(!connected && !connecting);
        btnDisconnect.setEnabled(connected);
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            IntentFilter filter = new IntentFilter("TNMOWER_STATUS");

            ContextCompat.registerReceiver(
                    this,
                    statusReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1001) {

            boolean granted = true;

            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }

            if (granted) {
                // ❌ ห้ามเปิด DeviceList อัตโนมัติ
                Toast.makeText(this, "พร้อมใช้งาน Bluetooth แล้ว", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "ไม่ได้รับอนุญาต Bluetooth", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

