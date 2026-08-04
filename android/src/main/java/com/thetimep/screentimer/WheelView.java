package com.thetimep.screentimer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.List;

/**
 * A scroll wheel that mimics the web prototype's wheels:
 * - flings and snaps to the centered item
 * - highlights the selection with a rounded box
 * - fades items out at the top and bottom edges
 * - fires a listener (and a haptic tick) when the selection changes
 */
public class WheelView extends View {

    public interface OnItemSelectedListener {
        void onItemSelected(int index);
    }

    private final List<String> items = new ArrayList<>();
    private final Paint itemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimTop = new Paint();
    private final Paint scrimBottom = new Paint();

    private final Scroller scroller;
    private VelocityTracker velocityTracker;

    private float itemHeight;
    private float scrollY = 0;
    private float maxScroll = 0;
    private int selectedIndex = 0;
    private boolean dragging = false;
    private float lastTouchY;
    private OnItemSelectedListener listener;
    private Runnable onTouchDown;
    private boolean tickEnabled = false;
    private AudioTrack tickTrack;

    public WheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        itemHeight = dp(56);
        scroller = new Scroller(context, new DecelerateInterpolator(2.0f));
        highlightPaint.setColor(0x14FFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(0x1AFFFFFF);
        itemPaint.setTextAlign(Paint.Align.CENTER);
        itemPaint.setColor(0xFF666666);
    }

    public void setItems(List<String> values, int initialIndex) {
        items.clear();
        items.addAll(values);
        selectedIndex = Math.max(0, Math.min(initialIndex, items.size() - 1));
        scrollY = selectedIndex * itemHeight;
        maxScroll = (items.size() - 1) * itemHeight;
        invalidate();
    }

    public int getSelectedIndex() { return selectedIndex; }
    public String getSelectedValue() { return items.isEmpty() ? "" : items.get(selectedIndex); }

    public void setOnItemSelectedListener(OnItemSelectedListener l) { listener = l; }

    /** Fired on every ACTION_DOWN so the parent can activate this wheel's mode. */
    public void setOnTouchDownListener(Runnable r) { onTouchDown = r; }

    public void setTickSoundEnabled(boolean on) { tickEnabled = on; }

    /** Soft sine tick like the web prototype's synthesized haptic audio. */
    private void playTick() {
        if (!tickEnabled) return;
        if (tickTrack == null) {
            try {
                int sampleRate = 44100;
                int dur = 55;
                int n = sampleRate * dur / 1000;
                short[] buf = new short[n];
                for (int i = 0; i < n; i++) {
                    double t = (double) i / sampleRate;
                    double env = Math.exp(-t * 85.0);
                    buf[i] = (short) (Math.sin(2 * Math.PI * 1000 * t) * env * 15000);
                }
                tickTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        n * 2, AudioTrack.MODE_STATIC);
                tickTrack.write(buf, 0, buf.length);
            } catch (Exception ignored) {}
        }
        try {
            tickTrack.setPlaybackHeadPosition(0);
            tickTrack.play();
        } catch (Exception ignored) {}
    }

    private void updateSelection() {
        if (items.isEmpty()) return;
        int idx = Math.round(scrollY / itemHeight);
        idx = Math.max(0, Math.min(items.size() - 1, idx));
        if (idx != selectedIndex) {
            selectedIndex = idx;
            if (listener != null) listener.onItemSelected(idx);
            playTick();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scrimTop.setShader(new LinearGradient(0, 0, 0, itemHeight * 0.7f,
                0xFF121212, 0x00121212, Shader.TileMode.CLAMP));
        scrimBottom.setShader(new LinearGradient(0, h - itemHeight * 0.7f, 0, h,
                0x00121212, 0xFF121212, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = getHeight() / 2f;

        // selection highlight box
        float hw = getWidth() * 0.78f;
        RectF box = new RectF((getWidth() - hw) / 2f, cy - itemHeight / 2f,
                (getWidth() + hw) / 2f, cy + itemHeight / 2f);
        float radius = itemHeight * 0.24f;
        canvas.drawRoundRect(box, radius, radius, highlightPaint);
        canvas.drawRoundRect(box, radius, radius, borderPaint);

        if (items.isEmpty()) return;
        int first = Math.max(0, (int) Math.floor((scrollY - itemHeight) / itemHeight));
        int last = Math.min(items.size() - 1, (int) Math.ceil((scrollY + getHeight() + itemHeight) / itemHeight));
        for (int i = first; i <= last; i++) {
            float yc = i * itemHeight - scrollY + cy;
            boolean selected = (i == selectedIndex);
            itemPaint.setColor(selected ? 0xFFFFFFFF : 0xFF666666);
            itemPaint.setTextSize(selected ? itemHeight * 0.52f : itemHeight * 0.46f);
            itemPaint.setFakeBoldText(selected);
            float baseline = yc - (itemPaint.ascent() + itemPaint.descent()) / 2f;
            canvas.drawText(items.get(i), getWidth() / 2f, baseline, itemPaint);
        }

        // top / bottom fade
        canvas.drawRect(0, 0, getWidth(), itemHeight * 0.7f, scrimTop);
        canvas.drawRect(0, getHeight() - itemHeight * 0.7f, getWidth(), getHeight(), scrimBottom);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = true;
                lastTouchY = e.getY();
                if (onTouchDown != null) onTouchDown.run();
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(e);
                scroller.forceFinished(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - lastTouchY;
                lastTouchY = e.getY();
                scrollY = clamp(scrollY - dy);
                updateSelection();
                if (velocityTracker != null) velocityTracker.addMovement(e);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                dragging = false;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(e);
                    velocityTracker.computeCurrentVelocity(1000);
                    float v = velocityTracker.getYVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                    if (Math.abs(v) > 500) {
                        scroller.fling(0, (int) scrollY, 0, (int) -v, 0, 0, 0, (int) maxScroll);
                        postInvalidateOnAnimation();
                    } else {
                        snapToSelected();
                    }
                } else {
                    snapToSelected();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
                snapToSelected();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = clamp(scroller.getCurrY());
            updateSelection();
            invalidate();
        } else if (!dragging) {
            snapToSelected();
        }
    }

    private void snapToSelected() {
        int target = Math.round(scrollY / itemHeight);
        target = Math.max(0, Math.min(items.size() - 1, target));
        int targetPx = (int) (target * itemHeight);
        if (Math.abs(scrollY - targetPx) > 1 && !scroller.computeScrollOffset()) {
            scroller.startScroll(0, (int) scrollY, 0, targetPx - (int) scrollY, 180);
            postInvalidateOnAnimation();
        }
        if (selectedIndex != target) {
            selectedIndex = target;
            if (listener != null) listener.onItemSelected(target);
            playTick();
            invalidate();
        }
    }

    private float clamp(float v) {
        return Math.max(0, Math.min(maxScroll, v));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
