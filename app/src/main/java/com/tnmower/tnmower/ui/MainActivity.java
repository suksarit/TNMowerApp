package com.tnmower.tnmower.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tnmower.tnmower.R;
import com.tnmower.tnmower.bluetooth.BluetoothService;

public class MainActivity extends AppCompatActivity {

    private GaugeView gaugeVolt;
    private GaugeView gaugeCurrent;
    private GaugeView gaugeTemp;

    private TextView txtVolt;
    private TextView txtCurrent;
    private TextView txtTemp;
    private TextView txtStatus;

    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnStop;

    private static final int REQ_BT = 100;
    private String selectedMAC = "";

    private float targetVolt = 0;
    private float targetCurrent = 0;
    private float targetTemp = 0;

    private float displayVolt = 0;
    private float displayCurrent = 0;
    private float displayTemp = 0;

    // 🔴 FIX: ใช้ field + คุม loop
    private final Handler handler = new Handler();
    private boolean isLoopRunning = false;

    private boolean fault = false;
    private boolean warning = false;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            updateStatusText(status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
        selectedMAC = sp.getString("MAC", "");

        gaugeVolt = findViewById(R.id.gaugeVolt);
        gaugeCurrent = findViewById(R.id.gaugeCurrent);
        gaugeTemp = findViewById(R.id.gaugeTemp);

        txtVolt = findViewById(R.id.txtVolt);
        txtCurrent = findViewById(R.id.txtCurrent);
        txtTemp = findViewById(R.id.txtTemp);
        txtStatus = findViewById(R.id.txtStatus);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnStop = findViewById(R.id.btnStop);

        txtStatus.setText(getString(R.string.status_disconnected));

        gaugeVolt.setMaxValue(30);
        gaugeCurrent.setMaxValue(50);
        gaugeTemp.setMaxValue(120);

        btnConnect.setOnClickListener(v -> {
            if (selectedMAC.isEmpty()) {
                Intent i = new Intent(this, DeviceListActivity.class);
                startActivityForResult(i, REQ_BT);
            } else {
                startBluetooth(selectedMAC);
            }
        });

        btnDisconnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, BluetoothService.class);
            stopService(intent);
            updateStatusText("DISCONNECTED");
        });

        btnStop.setOnClickListener(v -> sendStopCommand());

        BluetoothService.setTelemetryListener((volt, current, temp) -> {

            fault = false;
            warning = false;

            if (volt < 0 || volt > 30) fault = true;
            if (current < 0 || current > 50) fault = true;

            if (temp > 120) fault = true;
            else if (temp > 100) warning = true;

            if (!fault) {
                targetVolt = clamp(volt, 0, 30);
                targetCurrent = clamp(current, 0, 50);
                targetTemp = clamp(temp, 0, 120);
            }

            runOnUiThread(this::updateStatusUI);
        });

        // 🔴 เริ่ม loop ครั้งแรก
        startSmoothLoop();

        if (!selectedMAC.isEmpty()) {
            startBluetooth(selectedMAC);
        }
    }

    private void startBluetooth(String mac) {

        selectedMAC = mac;

        SharedPreferences sp = getSharedPreferences("TN_MOWER", MODE_PRIVATE);
        sp.edit().putString("MAC", mac).apply();

        Intent intent = new Intent(this, BluetoothService.class);
        intent.putExtra("MAC", mac);
        startService(intent);

        updateStatusText("CONNECTING");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_BT && resultCode == RESULT_OK) {
            String mac = data.getStringExtra("MAC");
            startBluetooth(mac);
        }
    }

    private void updateStatusText(String status) {

        runOnUiThread(() -> {

            switch (status) {

                case "CONNECTING":
                case "RECONNECTING":
                    txtStatus.setText(getString(R.string.status_connecting));
                    txtStatus.setTextColor(Color.YELLOW);
                    break;

                case "CONNECTED":
                    txtStatus.setText(getString(R.string.status_connected));
                    txtStatus.setTextColor(Color.GREEN);
                    break;

                case "TIMEOUT":
                    txtStatus.setText("หมดเวลา");
                    txtStatus.setTextColor(Color.RED);
                    break;

                case "DISCONNECTED":
                    txtStatus.setText(getString(R.string.status_disconnected));
                    txtStatus.setTextColor(Color.GRAY);
                    break;
            }
        });
    }

    // 🔴 FIX LOOP ตัวจริง
    private void startSmoothLoop() {

        if (isLoopRunning) return;

        isLoopRunning = true;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {

                if (!isLoopRunning) return;

                float alpha = 0.1f;

                displayVolt += (targetVolt - displayVolt) * alpha;
                displayCurrent += (targetCurrent - displayCurrent) * alpha;
                displayTemp += (targetTemp - displayTemp) * alpha;

                updateGauge();

                handler.postDelayed(this, 16);
            }
        }, 100);
    }

    private void stopSmoothLoop() {
        isLoopRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void updateGauge() {

        gaugeVolt.setValue(displayVolt);
        gaugeCurrent.setValue(displayCurrent);
        gaugeTemp.setValue(displayTemp);

        txtVolt.setText(String.format(getString(R.string.format_voltage), displayVolt));
        txtCurrent.setText(String.format(getString(R.string.format_current), displayCurrent));
        txtTemp.setText(String.format(getString(R.string.format_temp), displayTemp));
    }

    private void updateStatusUI() {

        if (fault) {
            txtStatus.setText("ผิดปกติ !");
            txtStatus.setTextColor(Color.RED);
        }
        else if (warning) {
            txtStatus.setText("เตือน !");
            txtStatus.setTextColor(Color.YELLOW);
        }
        else {
            txtStatus.setText(getString(R.string.status_connected));
            txtStatus.setTextColor(Color.GREEN);
        }
    }

    private void sendStopCommand() {
        try {
            Intent intent = new Intent(this, BluetoothService.class);
            intent.putExtra("cmd", "STOP");
            startService(intent);
        } catch (Exception ignored) {}
    }

    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();

        startSmoothLoop(); // 🔴 เริ่ม loop

        IntentFilter filter = new IntentFilter("TNMOWER_STATUS");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopSmoothLoop(); // 🔴 หยุด loop

        unregisterReceiver(statusReceiver);
    }
}