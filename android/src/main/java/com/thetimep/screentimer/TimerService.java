package com.thetimep.screentimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Foreground service that runs the countdown, shows the floating widget,
 * checks the battery kill-switch and forces the screen off when time is up.
 */
public class TimerService extends Service {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_END_TIME = "end_time";
    public static final String EXTRA_BATTERY = "battery_threshold";

    public static final int MODE_TIMER = 0;
    public static final int MODE_SLEEP = 1;
    public static final int MODE_BATTERY = 2;

    private static final String CHANNEL_ID = "sleep_timer";
    private static final int NOTIF_ID = 1001;

    // Shared state (same process as overlay + activity)
    private static volatile long endTime = 0L;
    private static volatile long pendingExtendSec = 0L;
    private static volatile int batteryThreshold = 0;
    private static volatile int mode = MODE_TIMER;
    private static volatile boolean running = false;
    private static volatile OverlayView overlayRef = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private OverlayView overlayView;
    private NotificationManager notificationManager;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            // Battery kill-switch works as a secondary trigger in any mode.
            if (batteryThreshold > 0 && getBatteryLevel() >= 0
                    && getBatteryLevel() <= batteryThreshold) {
                finish(true);
                return;
            }
            long remainingSec = getRemainingSec();
            if (mode != MODE_BATTERY && remainingSec <= 0 && pendingExtendSec == 0) {
                finish(true);
                return;
            }
            updateUi();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------- static control API ----------

    public static void start(Context ctx, int m, long end, int battery) {
        Intent i = new Intent(ctx, TimerService.class);
        i.putExtra(EXTRA_MODE, m);
        i.putExtra(EXTRA_END_TIME, end);
        i.putExtra(EXTRA_BATTERY, battery);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, TimerService.class));
    }

    public static boolean isRunning() { return running; }
    public static int getMode() { return mode; }
    public static long getEndTime() { return endTime; }

    public static long getRemainingSec() {
        long rem = (endTime - System.currentTimeMillis()) / 1000L;
        if (rem < 0) rem = 0;
        return rem + pendingExtendSec;
    }

    public static void extendBySeconds(long sec) { pendingExtendSec = sec; }

    public static void commitExtend() {
        if (pendingExtendSec > 0) endTime += pendingExtendSec * 1000L;
        pendingExtendSec = 0;
    }

    public static String formatTime(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    // ---------- lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            notificationManager.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mode = intent.getIntExtra(EXTRA_MODE, MODE_TIMER);
            endTime = intent.getLongExtra(EXTRA_END_TIME, System.currentTimeMillis() + 15 * 60 * 1000L);
            batteryThreshold = intent.getIntExtra(EXTRA_BATTERY, 0);
        }
        running = true;
        startForeground(NOTIF_ID, buildNotification(displayText()));
        showOverlay();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(ticker);
        hideOverlay();
        if (notificationManager != null) notificationManager.cancel(NOTIF_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- helpers ----------

    private String displayText() {
        if (mode == MODE_BATTERY) {
            int level = getBatteryLevel();
            if (level < 0) return "--";
            // Count down the points left until the floor: 60 - 50 = 10, 9, 8...
            int remaining = level - batteryThreshold;
            return String.valueOf(Math.max(0, remaining));
        }
        return formatTime(getRemainingSec());
    }

    private void updateUi() {
        String text = displayText();
        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIF_ID, buildNotification(text));
            } catch (Exception ignored) {
            }
        }
        if (overlayRef != null) overlayRef.updateText(text);
    }

    private void finish(boolean shouldLock) {
        running = false;
        handler.removeCallbacks(ticker);
        hideOverlay();
        if (shouldLock) {
            if (isAdminActive()) {
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                dpm.lockNow();
            } else {
                // Screen off requires Device Admin — tell the user why it didn't happen.
                Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);
                Notification n = nb
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.toast_admin_required))
                        .setSmallIcon(R.drawable.ic_stat_timer)
                        .setAutoCancel(true)
                        .build();
                try { notificationManager.notify(NOTIF_ID + 1, n); } catch (Exception ignored) {}
            }
        }
        Toast.makeText(this, R.string.toast_done, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    private boolean isAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return -1;
    }

    private void showOverlay() {
        hideOverlay();
        if (!Settings.canDrawOverlays(this)) return;
        overlayView = new OverlayView(this);
        overlayRef = overlayView;
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        overlayParams = new WindowManager.LayoutParams(
                dp(76), dp(40), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(16);
        overlayParams.y = dp(120);
        overlayView.attach(windowManager, overlayParams);
        overlayView.updateText(displayText());
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Exception ignored) {
        }
        overlayView.startGhostTimer();
    }

    private void hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
            overlayRef = null;
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.running_notif_title));
        if (mode == MODE_BATTERY) {
            b.setContentText(getString(R.string.running_notif_battery, batteryThreshold, text));
        } else {
            b.setContentText(getString(R.string.running_notif_text, text));
        }
        b.setSmallIcon(R.drawable.ic_stat_timer);
        b.setContentIntent(pi);
        b.setOngoing(true);
        b.setPriority(Notification.PRIORITY_LOW);
        Intent stop = new Intent(this, TimerService.class);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(R.drawable.ic_stat_timer, getString(R.string.stop), stopPi);
        return b.build();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
