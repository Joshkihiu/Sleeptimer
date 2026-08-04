package com.thetimep.screentimer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The floating "pill" widget shown over other apps.
 * - Drag anywhere to move it.
 * - Double-tap then drag RIGHT to extend the running timer (+1 min per 10px).
 * - Goes to ghost mode (15% opacity) after 5s of inactivity; touch wakes it.
 */
public class OverlayView extends View {

    private static final float WAKE_ALPHA = 0.90f;
    private static final float GHOST_ALPHA = 0.15f;
    private static final long GHOST_DELAY_MS = 3000L;

    private final Paint bgPaint;
    private final Paint borderPaint;
    private final Paint textPaint;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String text = "00:00";
    private long lastTapTime = 0;
    private boolean extendMode = false;
    private boolean dragging = false;
    private float downX, downY;      // raw coords at ACTION_DOWN
    private float totalDx = 0;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private final Runnable ghostRunnable = new Runnable() {
        @Override public void run() { OverlayView.this.setAlpha(GHOST_ALPHA); }
    };

    public OverlayView(Context c) {
        super(c);
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(242, 20, 20, 20));
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(Color.argb(100, 255, 255, 255));
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(14f));
        textPaint.setFakeBoldText(true);
        setAlpha(WAKE_ALPHA);
    }

    public void attach(WindowManager wm, WindowManager.LayoutParams lp) {
        windowManager = wm;
        params = lp;
    }

    public void updateText(String t) {
        text = t;
        resizeToFit();
        postInvalidate();
    }

    /** Resize the pill so it hugs the timer text, keeping its center in place. */
    private void resizeToFit() {
        if (windowManager == null || params == null) return;
        int padX = (int) dp(14);
        int padY = (int) dp(5);
        float th = textPaint.getFontMetrics().descent - textPaint.getFontMetrics().ascent;
        int w = (int) (textPaint.measureText(text) + padX * 2);
        int h = (int) (th + padY * 2);
        w = Math.max(w, (int) dp(52));
        h = Math.max(h, (int) dp(30));
        if (w == params.width && h == params.height) return;
        int oldW = params.width, oldH = params.height;
        params.width = w;
        params.height = h;
        params.x += (oldW - w) / 2;
        params.y += (oldH - h) / 2;
        try { windowManager.updateViewLayout(this, params); } catch (Exception ignored) {}
    }

    /** Wake the pill and (re)schedule ghost-dimming after 3s. */
    public void startGhostTimer() {
        setAlpha(WAKE_ALPHA);
        handler.removeCallbacks(ghostRunnable);
        handler.postDelayed(ghostRunnable, GHOST_DELAY_MS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        RectF r = new RectF(1, 1, w - 1, h - 1);
        canvas.drawRoundRect(r, h / 2, h / 2, bgPaint);
        canvas.drawRoundRect(r, h / 2, h / 2, borderPaint);
        float tw = textPaint.measureText(text);
        // correct vertical centering: baseline = h/2 - (descent - ascent)/2 - ascent
        float ty = (h - (textPaint.descent() - textPaint.ascent())) / 2 - textPaint.ascent();
        canvas.drawText(text, (w - tw) / 2, ty, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        startGhostTimer();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getRawX();
                downY = e.getRawY();
                totalDx = 0;
                long now = System.currentTimeMillis();
                extendMode = (now - lastTapTime < 300);
                dragging = false;
                lastTapTime = now;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (extendMode) {
                    totalDx = Math.max(0, dx);
                    TimerService.extendBySeconds((long) (totalDx / 10) * 60);
                    updateText(TimerService.formatTime(TimerService.getRemainingSec()));
                } else {
                    if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragging = true;
                    if (dragging && params != null && windowManager != null) {
                        int nx = (int) (downX - getWidth() / 2 + dx);
                        int ny = (int) (downY - getHeight() / 2 + dy);
                        if (nx < 0) nx = 0;
                        if (ny < 0) ny = 0;
                        try {
                            params.x = nx;
                            params.y = ny;
                            windowManager.updateViewLayout(this, params);
                        } catch (Exception ignored) {
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (extendMode) {
                    TimerService.commitExtend();
                    updateText(TimerService.formatTime(TimerService.getRemainingSec()));
                }
                extendMode = false;
                dragging = false;
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
