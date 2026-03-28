package com.tnmower.tnmower.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public class GaugeView extends View {

    private float value = 0f;
    private float displayValue = 0f;
    private float velocity = 0f;        // 🔴 inertia จริง
    private float peakValue = 0f;

    private float minValue = 0f;
    private float maxValue = 100f;

    private String unit = "";

    private Paint bgPaint, fgPaint, textPaint;
    private Paint rimPaint, glowPaint, needlePaint, tickPaint, peakPaint, centerPaint;

    private RectF rect = new RectF();

    private float strokeWidth = 18f;

    private boolean isPreview;

    private boolean flashState = false;
    private long lastFlashTime = 0;

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);

        isPreview = isInEditMode();

        // =========================
        // BASE
        // =========================
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(60, 255, 255, 255));
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeCap(Paint.Cap.ROUND);

        fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(Color.argb(200, 255, 255, 255));

        // 🔴 เงาขอบจริง
        rimPaint.setShadowLayer(20, 0, 0, Color.BLACK);
        setLayerType(LAYER_TYPE_SOFTWARE, rimPaint);

        // =========================
        // NEEDLE
        // =========================
        needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needlePaint.setColor(Color.WHITE);
        needlePaint.setStrokeWidth(10f);

        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setColor(Color.WHITE);

        // =========================
        // TICK
        // =========================
        tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickPaint.setColor(Color.LTGRAY);
        tickPaint.setStrokeWidth(3f);

        // =========================
        // PEAK
        // =========================
        peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        peakPaint.setColor(Color.CYAN);
        peakPaint.setStrokeWidth(6f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setValue(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return;

        if (v < minValue) v = minValue;
        if (v > maxValue) v = maxValue;

        this.value = v;

        if (v > peakValue) peakValue = v;

        postInvalidate();
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {

        int size = Math.min(w, h);

        strokeWidth = size * 0.08f;

        bgPaint.setStrokeWidth(strokeWidth);
        fgPaint.setStrokeWidth(strokeWidth);
        glowPaint.setStrokeWidth(strokeWidth + 10);
        rimPaint.setStrokeWidth(strokeWidth * 0.4f);

        textPaint.setTextSize(size * 0.22f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // =========================
        // 🔴 REAL INERTIA
        // =========================
        if (isPreview) {
            displayValue = 50;
            peakValue = 70;
        } else {
            float force = (value - displayValue) * 0.15f;
            velocity = velocity * 0.85f + force;
            displayValue += velocity;
        }

        // 🔴 PEAK DECAY
        peakValue -= (peakValue - displayValue) * 0.02f;

        int padding = (int)(strokeWidth);
        int size = Math.min(w, h) - padding * 2;

        rect.set(
                (w - size) / 2f,
                (h - size) / 2f,
                (w + size) / 2f,
                (h + size) / 2f
        );

        float percent = (displayValue - minValue) / (maxValue - minValue);
        float peakPercent = (peakValue - minValue) / (maxValue - minValue);

        percent = Math.max(0f, Math.min(percent, 1f));
        peakPercent = Math.max(0f, Math.min(peakPercent, 1f));

        // =========================
        // COLOR
        // =========================
        int color;
        if (percent < 0.7f) color = Color.GREEN;
        else if (percent < 0.9f) color = Color.rgb(255,180,0);
        else color = Color.RED;

        fgPaint.setColor(color);
        glowPaint.setColor(color);
        glowPaint.setAlpha(150);

        // 🔴 FLASH RED
        if (percent > 0.9f) {
            long now = System.currentTimeMillis();
            if (now - lastFlashTime > 250) {
                flashState = !flashState;
                lastFlashTime = now;
            }
            fgPaint.setColor(flashState ? Color.RED : Color.DKGRAY);
        }

        // =========================
        // BASE
        // =========================
        canvas.drawArc(rect, 180, 180, false, rimPaint);
        canvas.drawArc(rect, 180, 180, false, bgPaint);

        // =========================
        // TICKS
        // =========================
        for (int i = 0; i <= 10; i++) {

            float angle = (float)Math.toRadians(180 + i * 18);
            float len = (i % 5 == 0) ? 40 : 25;

            float x1 = w/2f + (size/2f - 10) * (float)Math.cos(angle);
            float y1 = h/2f + (size/2f - 10) * (float)Math.sin(angle);

            float x2 = w/2f + (size/2f - len) * (float)Math.cos(angle);
            float y2 = h/2f + (size/2f - len) * (float)Math.sin(angle);

            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        // =========================
        // ARC
        // =========================
        canvas.drawArc(rect, 180, percent * 180f, false, glowPaint);
        canvas.drawArc(rect, 180, percent * 180f, false, fgPaint);

        // =========================
        // NEEDLE
        // =========================
        float angle = (float)Math.toRadians(180 + percent * 180f);

        float nx = w/2f + (size/2f - 50) * (float)Math.cos(angle);
        float ny = h/2f + (size/2f - 50) * (float)Math.sin(angle);

        canvas.drawLine(w/2f, h/2f, nx, ny, needlePaint);
        canvas.drawCircle(w/2f, h/2f, 12, centerPaint);

        // =========================
        // PEAK
        // =========================
        float pAngle = (float)Math.toRadians(180 + peakPercent * 180f);

        float px = w/2f + (size/2f - 30) * (float)Math.cos(pAngle);
        float py = h/2f + (size/2f - 30) * (float)Math.sin(pAngle);

        canvas.drawCircle(px, py, 8, peakPaint);

        // =========================
        // TEXT
        // =========================
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = h/2f - (fm.ascent + fm.descent) / 2;

        String text = (unit == null || unit.isEmpty())
                ? String.format(Locale.US, "%.0f", displayValue)
                : String.format(Locale.US, "%.1f %s", displayValue, unit);

        canvas.drawText(text, w/2f, textY, textPaint);

        if (!isPreview) {
            postInvalidateOnAnimation();
        }
    }
}
