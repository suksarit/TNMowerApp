package com.tnmower.tnmower.ui;

import android.content.*;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tnmower.tnmower.R;
import com.tnmower.tnmower.bluetooth.BluetoothService;

public class MainActivity extends AppCompatActivity {

    private GaugeView gaugeVolt, gaugeCurrentL, gaugeCurrentR, gaugeTempL, gaugeTempR;

    private TextView txtVolt, txtStatus;
    private TextView txtTempL, txtTempR;
    private TextView txtM1, txtM2, txtM3, txtM4;

    private Button btnConnect, btnDisconnect, btnStop;

    private static final int REQ_BT = 100;
    private String selectedMAC = "";

    private boolean connected = false;
    private boolean connecting = false;

    private Handler reconnectHandler = new Handler();
    private Runnable reconnectRunnable;

    private static final int RECONNECT_DELAY = 3000;
    private static final int CONNECT_TIMEOUT = 5000;

    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT = 5;

    private float targetVolt = 0, displayVolt = 0;
    private float targetCurrentL = 0, targetCurrentR = 0;
    private float displayCurrentL = 0, displayCurrentR = 0;
    private float targetTempL = 0, targetTempR = 0;
    private float displayTempL = 0, displayTempR = 0;

    private float tempL = 0, tempR = 0;
    private float m1 = 0, m2 = 0, m3 = 0, m4 = 0;

    private final Handler handler = new Handler();
    private boolean isLoopRunning = false;

    private boolean isReceiverRegistered = false;

    private Vibrator vibrator;

    // ===============================
    // 🔴 FIX: รับสถานะ + reconnect
    // ===============================
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
                scheduleReconnect(); // 🔥 reconnect จริง
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

        gaugeVolt = findViewById(R.id.gaugeVolt);
        gaugeCurrentL = findViewById(R.id.gaugeCurrentL);
        gaugeCurrentR = findViewById(R.id.gaugeCurrentR);
        gaugeTempL = findViewById(R.id.gaugeTempL);
        gaugeTempR = findViewById(R.id.gaugeTempR);

        gaugeVolt.setUnit("V");
        gaugeCurrentL.setUnit("A");
        gaugeCurrentR.setUnit("A");
        gaugeTempL.setUnit("°C");
        gaugeTempR.setUnit("°C");

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
                startActivityForResult(
                        new Intent(this, DeviceListActivity.class), REQ_BT);
            } else {
                startBluetooth(selectedMAC);
            }
        });

        btnDisconnect.setOnClickListener(v -> {
            stopReconnect();
            stopService(new Intent(this, BluetoothService.class));

            connected = false;
            connecting = false;

            txtStatus.setText("สถานะ: ตัดการเชื่อมต่อ");
            txtStatus.setTextColor(Color.RED);

            updateButtonState();
        });

        btnStop.setOnClickListener(v -> sendStopCommand());

        // ===============================
        // 🔴 TELEMETRY
        // ===============================
        BluetoothService.setTelemetryListener((volt, m1, m2, m3, m4, tL, tR) -> {

            this.m1 = m1;
            this.m2 = m2;
            this.m3 = m3;
            this.m4 = m4;

            tempL = tL;
            tempR = tR;

            targetVolt = clamp(volt, 0, 30);
            targetCurrentL = clamp(m1 + m2, 0, 100);
            targetCurrentR = clamp(m3 + m4, 0, 100);
            targetTempL = clamp(tL, 0, 120);
            targetTempR = clamp(tR, 0, 120);

            runOnUiThread(() -> {
                updateTempText();
                updateMotorText(); // 🔥 logic ใหม่อยู่ตรงนี้
                checkDanger();
            });
        });

        startSmoothLoop();

        if (!selectedMAC.isEmpty()) {
            startBluetooth(selectedMAC);
        }
    }

    // ===============================
    // 🔴 CONNECT + TIMEOUT
    // ===============================
    private void startBluetooth(String mac) {

        if (connecting) return;

        connecting = true;
        connected = false;

        txtStatus.setText("กำลังเชื่อมต่อ...");
        txtStatus.setTextColor(Color.YELLOW);

        updateButtonState();

        Intent intent = new Intent(this, BluetoothService.class);
        intent.putExtra("MAC", mac);
        startService(intent);

        new Handler().postDelayed(() -> {
            if (!connected) {
                connecting = false;

                txtStatus.setText("เชื่อมต่อไม่สำเร็จ");
                txtStatus.setTextColor(Color.RED);

                scheduleReconnect(); // 🔥 สำคัญ
                updateButtonState();
            }
        }, CONNECT_TIMEOUT);
    }

    // ===============================
    // 🔴 RECONNECT
    // ===============================
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

    // ===============================
    // 🔴 SMOOTH LOOP
    // ===============================
    private void startSmoothLoop() {

        if (isLoopRunning) return;
        isLoopRunning = true;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {

                if (!isLoopRunning) return;

                float alpha = 0.1f;

                displayVolt += (targetVolt - displayVolt) * alpha;
                displayCurrentL += (targetCurrentL - displayCurrentL) * alpha;
                displayCurrentR += (targetCurrentR - displayCurrentR) * alpha;
                displayTempL += (targetTempL - displayTempL) * alpha;
                displayTempR += (targetTempR - displayTempR) * alpha;

                updateGauge();

                handler.postDelayed(this, 16);
            }
        }, 100);
    }

    private void updateGauge() {

        gaugeVolt.setValue(displayVolt);
        gaugeCurrentL.setValue(displayCurrentL);
        gaugeCurrentR.setValue(displayCurrentR);
        gaugeTempL.setValue(displayTempL);
        gaugeTempR.setValue(displayTempR);

        txtVolt.setText(String.format("แรงดัน = %.2f V", displayVolt));
    }

    private void updateTempText() {

        txtTempL.setText(String.format("ซ้าย = %.0f °C", tempL));
        txtTempR.setText(String.format("ขวา = %.0f °C", tempR));

        txtTempL.setTextColor(tempL > 80 ? Color.RED : Color.WHITE);
        txtTempR.setTextColor(tempR > 80 ? Color.RED : Color.WHITE);
    }

    // ===============================
    // 🔴 MOTOR + ALERT + HIGHLIGHT
    // ===============================
    private void updateMotorText() {

        txtM1.setText(String.format("M1 = %.2f A", m1));
        txtM2.setText(String.format("M2 = %.2f A", m2));
        txtM3.setText(String.format("M3 = %.2f A", m3));
        txtM4.setText(String.format("M4 = %.2f A", m4));

        float limit = 30f;

        boolean m1Over = m1 > limit;
        boolean m2Over = m2 > limit;
        boolean m3Over = m3 > limit;
        boolean m4Over = m4 > limit;

        txtM1.setTextColor(m1Over ? Color.RED : Color.WHITE);
        txtM2.setTextColor(m2Over ? Color.RED : Color.WHITE);
        txtM3.setTextColor(m3Over ? Color.RED : Color.WHITE);
        txtM4.setTextColor(m4Over ? Color.RED : Color.WHITE);

        // 🔴 highlight ฝั่งด้วยค่า (ไม่ใช่ setColor)
        float leftSum = m1 + m2;
        float rightSum = m3 + m4;

        gaugeCurrentL.setValue(leftSum);
        gaugeCurrentR.setValue(rightSum);

        // 🔴 แจ้งเตือน
        if (m1Over || m2Over || m3Over || m4Over) {
            vibrateAlert();
        }
    }

    private void vibrateAlert() {
        if (vibrator == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(200);
        }
    }

    private void checkDanger() {
        if ((tempL > 80 || tempR > 80)) {
            vibrateAlert();
        }
    }

    private void sendStopCommand() {
        try {
            Intent intent = new Intent(this, BluetoothService.class);
            intent.putExtra("cmd", "STOP");
            startService(intent);
        } catch (Exception ignored) {}
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private void updateButtonState() {
        btnConnect.setEnabled(!connected && !connecting);
        btnDisconnect.setEnabled(connected);
    }

    private void updateStatusText(String status) {
        txtStatus.setText("สถานะ: " + status);
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

