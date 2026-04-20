package com.tnmower.tnmower.model;

public class TelemetryData {

    // =========================
    // 🔴 LIMIT (ให้ตรง Arduino)
    // =========================
    private static final float MAX_VOLT = 60f;
    private static final float MAX_CURRENT = 150f;
    private static final float MAX_TEMP = 120f;

    // =========================
    // 🔴 SEQUENCE
    // =========================
    public int seq;

    // =========================
    // 🔴 TIME (เพิ่มใหม่)
    // =========================
    public long timestamp;

    // =========================
    // 🔴 FLAGS + ERROR
    // =========================
    public int flags;
    public int error;

    // =========================
    // 🔴 DATA
    // =========================
    public float volt;

    public float m1;
    public float m2;
    public float m3;
    public float m4;

    public float tempL;
    public float tempR;

    // ==================================================
    // 🔴 CONSTRUCTOR
    // ==================================================
    public TelemetryData(int seq, int flags, int error,
                         float volt,
                         float m1,
                         float m2,
                         float m3,
                         float m4,
                         float tempL,
                         float tempR) {

        this.seq = seq;
        this.flags = flags;
        this.error = error;

        // 🔴 timestamp ตอนรับ packet
        this.timestamp = System.currentTimeMillis();

        this.volt = clamp(volt, 0, MAX_VOLT);

        this.m1 = clamp(m1, 0, MAX_CURRENT);
        this.m2 = clamp(m2, 0, MAX_CURRENT);
        this.m3 = clamp(m3, 0, MAX_CURRENT);
        this.m4 = clamp(m4, 0, MAX_CURRENT);

        this.tempL = clamp(tempL, -20, MAX_TEMP);
        this.tempR = clamp(tempR, -20, MAX_TEMP);
    }

    // ==================================================
    // 🔴 HELPER
    // ==================================================
    public float getAverageCurrent() {
        return (m1 + m2 + m3 + m4) * 0.25f;
    }

    public float getMaxTemp() {
        return Math.max(tempL, tempR);
    }

    // ==================================================
    // 🔴 SYSTEM STATUS
    // ==================================================
    public boolean isLocked() {
        return (flags & 0x01) != 0;
    }

    public boolean isFailsafe() {
        return (flags & 0x02) != 0;
    }

    public boolean isEngineRunning() {
        return (flags & 0x04) != 0;
    }

    public boolean hasErrorCode() {
        return error != 0;
    }

    // ==================================================
    // 🔴 ALERT SYSTEM
    // ==================================================
    public boolean isVoltageLow() {
        return volt < 20f;
    }

    public boolean isCurrentHigh() {
        return m1 > 60 || m2 > 60 || m3 > 60 || m4 > 60;
    }

    public boolean isTempHigh() {
        return tempL > 80 || tempR > 80;
    }

    public boolean hasError() {
        return hasErrorCode() || isVoltageLow() || isCurrentHigh() || isTempHigh();
    }

    // ==================================================
    // 🔴 SENSOR FAULT (เพิ่มใหม่)
    // ==================================================
    public boolean hasSensorFault() {
        return m1 < 0 || m2 < 0 || m3 < 0 || m4 < 0;
    }

    // ==================================================
    // 🔴 SEQ CHECK
    // ==================================================
    public boolean isSeqJump(int lastSeq) {
        int diff = (seq - lastSeq) & 0xFF;
        return diff > 1;
    }

    // ==================================================
    // 🔴 STALE DATA CHECK (สำคัญมาก)
    // ==================================================
    public boolean isStale(long now) {
        return (now - timestamp) > 500;
    }

    // ==================================================
    // 🔴 VALIDATION
    // ==================================================
    public boolean isValid() {

        if (Float.isNaN(volt)) return false;

        if (Float.isNaN(m1) || Float.isNaN(m2) ||
                Float.isNaN(m3) || Float.isNaN(m4)) return false;

        return !Float.isNaN(tempL) && !Float.isNaN(tempR);
    }

    // ==================================================
    // 🔴 CLAMP
    // ==================================================
    private float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

