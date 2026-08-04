package com.thetimep.screentimer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class MainActivity extends Activity {

    private View sectionDuration, sectionExact, sectionBattery;
    private TextView batteryLabel, currentBattery;
    private WheelView wheelDuration, wheelH, wheelM, wheelBattery;
    private ImageButton btnPlay;
    private View permissionsView;
    private Switch swOverlay, swNotify, swBattery, swAdmin, swAutoStart;
    private TextView stOverlay, stNotify, stBattery, stAdmin;
    private boolean suppressAutoStart = false;

    private static final String PREFS = "screentimer_prefs";
    private static final String KEY_AUTO_START = "auto_start_on_exit";
    private SharedPreferences prefs;

    private String activeMode = "timer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sectionDuration = findViewById(R.id.sectionDuration);
        sectionExact = findViewById(R.id.sectionExact);
        sectionBattery = findViewById(R.id.sectionBattery);
        batteryLabel = findViewById(R.id.batteryLabel);
        currentBattery = findViewById(R.id.currentBattery);
        wheelDuration = findViewById(R.id.wheelDuration);
        wheelH = findViewById(R.id.wheelH);
        wheelM = findViewById(R.id.wheelM);
        wheelBattery = findViewById(R.id.wheelBattery);
        btnPlay = findViewById(R.id.btnPlay);
        permissionsView = findViewById(R.id.permissionsView);
        swOverlay = findViewById(R.id.swOverlay);
        swNotify = findViewById(R.id.swNotify);
        swBattery = findViewById(R.id.swBattery);
        swAdmin = findViewById(R.id.swAdmin);
        stOverlay = findViewById(R.id.stOverlay);
        stNotify = findViewById(R.id.stNotify);
        stBattery = findViewById(R.id.stBattery);
        stAdmin = findViewById(R.id.stAdmin);
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        ImageButton btnPermBack = findViewById(R.id.btnPermBack);

        // --- wheels ---
        List<String> mins = new ArrayList<>();
        for (int i = 1; i <= 120; i++) mins.add(String.valueOf(i));
        wheelDuration.setItems(mins, 14); // 15 minutes

        List<String> hours = new ArrayList<>();
        for (int i = 0; i < 24; i++) hours.add(String.format("%02d", i));
        wheelH.setItems(hours, Calendar.getInstance().get(Calendar.HOUR_OF_DAY));

        List<String> minutes = new ArrayList<>();
        for (int i = 0; i < 60; i++) minutes.add(String.format("%02d", i));
        wheelM.setItems(minutes, (Calendar.getInstance().get(Calendar.MINUTE) + 15) % 60);

        refreshBatteryWheel(); // capped at the current battery level

        // Touching a wheel activates its mode, like the prototype's pointerdown.
        wheelDuration.setOnTouchDownListener(() -> setMode("timer"));
        wheelH.setOnTouchDownListener(() -> setMode("sleep"));
        wheelM.setOnTouchDownListener(() -> setMode("sleep"));
        wheelBattery.setOnTouchDownListener(() -> setMode("battery"));

        // --- interactions ---
        sectionDuration.setOnClickListener(v -> setMode("timer"));
        sectionExact.setOnClickListener(v -> setMode("sleep"));
        sectionBattery.setOnClickListener(v -> setMode("battery"));
        batteryLabel.setOnClickListener(v -> setMode("battery"));

        btnSettings.setOnClickListener(v -> openPermissions());
        btnPermBack.setOnClickListener(v -> closePermissions());

        btnPlay.setOnClickListener(v -> {
            if (TimerService.isRunning()) {
                TimerService.stop(this);
                Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
            } else {
                computeAndStart(true);
            }
            refreshRunningUi();
        });

        // Permission switches open the matching settings screen
        swOverlay.setOnClickListener(v -> openOverlaySettings());
        swNotify.setOnClickListener(v -> openNotificationSettings());
        swBattery.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        swAdmin.setOnClickListener(v -> requestAdmin());

        // --- general settings ---
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        swAutoStart = findViewById(R.id.swAutoStart);
        swAutoStart.setChecked(prefs.getBoolean(KEY_AUTO_START, true));
        swAutoStart.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(KEY_AUTO_START, c).apply());

        setMode("timer");
    }

    @Override
    protected void onResume() {
        super.onResume();
        suppressAutoStart = false;
        refreshBattery();
        refreshBatteryWheel();
        refreshPermissions();
        refreshRunningUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app (home / app switch) starts the timer automatically,
        // mirroring the prototype's system-Home button (unless turned off in Settings).
        if (prefs != null
                && prefs.getBoolean(KEY_AUTO_START, true)
                && !suppressAutoStart && !TimerService.isRunning() && !isChangingConfigurations()) {
            autoStartTimer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissions();
    }

    // ---------- mode / active states (mirrors the 30/50/20 prototype) ----------

    private void setMode(String mode) {
        activeMode = mode;
        applyActiveStates();
    }

    private void applyActiveStates() {
        sectionDuration.setAlpha(activeMode.equals("timer") ? 1f : 0.3f);
        sectionExact.setAlpha(activeMode.equals("sleep") ? 1f : 0.3f);
        sectionBattery.setAlpha(activeMode.equals("battery") ? 1f : 0.3f);
    }

    // ---------- start / stop ----------

    private void startTimer() {
        computeAndStart(true);
    }

    private void autoStartTimer() {
        computeAndStart(false);
    }

    private void computeAndStart(boolean userInitiated) {
        if (TimerService.isRunning()) return;
        int m;
        long end;
        int battery = 0;
        if (activeMode.equals("timer")) {
            m = TimerService.MODE_TIMER;
            end = System.currentTimeMillis() + (wheelDuration.getSelectedIndex() + 1) * 60L * 1000L;
        } else if (activeMode.equals("sleep")) {
            m = TimerService.MODE_SLEEP;
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, wheelH.getSelectedIndex());
            c.set(Calendar.MINUTE, wheelM.getSelectedIndex());
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
            end = c.getTimeInMillis();
        } else {
            m = TimerService.MODE_BATTERY;
            battery = wheelBattery.getSelectedIndex() + 1;
            end = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L; // battery-only: no time finish
        }

        if (userInitiated) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            boolean missingOverlay = !Settings.canDrawOverlays(this);
            boolean missingAdmin = !((DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE))
                    .isAdminActive(new ComponentName(this, AdminReceiver.class));
            boolean missingBattery = !((PowerManager) getSystemService(POWER_SERVICE))
                    .isIgnoringBatteryOptimizations(getPackageName());
            if (missingOverlay || missingAdmin || missingBattery) {
                promptPermissions(missingOverlay, missingAdmin, missingBattery, m, end, battery);
                return;
            }
        }
        doStart(m, end, battery);
    }

    private void promptPermissions(boolean missingOverlay, boolean missingAdmin, boolean missingBattery,
                                   int m, long end, int battery) {
        StringBuilder sb = new StringBuilder(getString(R.string.perm_prompt_header));
        if (missingOverlay) sb.append(getString(R.string.perm_prompt_overlay));
        if (missingAdmin) sb.append(getString(R.string.perm_prompt_admin));
        if (missingBattery) sb.append(getString(R.string.perm_prompt_battery));
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_prompt_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.perm_prompt_grant, (d, w) -> openPermissions())
                .setNegativeButton(R.string.perm_prompt_later, (d, w) -> doStart(m, end, battery))
                .show();
    }

    private void doStart(int m, long end, int battery) {
        try {
            TimerService.start(this, m, end, battery);
        } catch (Exception e) {
            // OS blocked a background FGS start — tell the user, timer did not start.
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
        }
        refreshRunningUi();
    }

    private void refreshRunningUi() {
        btnPlay.setImageResource(TimerService.isRunning() ? R.drawable.ic_stop : R.drawable.ic_play);
    }

    // ---------- battery ----------

    private void refreshBattery() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int level = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        if (level >= 0) currentBattery.setText(getString(R.string.current_battery, level));
        else currentBattery.setText("");
    }

    /** The battery wheel never offers a threshold above the current charge. */
    private void refreshBatteryWheel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int level = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        if (level < 1) return;
        int current = wheelBattery.getSelectedIndex() + 1;
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= level; i++) items.add(String.valueOf(i));
        int sel = Math.max(0, Math.min(current - 1, items.size() - 1));
        wheelBattery.setItems(items, sel);
    }

    // ---------- permissions view ----------

    private void openPermissions() {
        int w = getResources().getDisplayMetrics().widthPixels;
        permissionsView.setVisibility(View.VISIBLE);
        permissionsView.setTranslationX(w);
        permissionsView.animate().translationX(0).setDuration(240).start();
        refreshPermissions();
    }

    private void closePermissions() {
        int w = getResources().getDisplayMetrics().widthPixels;
        permissionsView.animate().translationX(w).setDuration(240)
                .withEndAction(() -> permissionsView.setVisibility(View.GONE)).start();
    }

    private void refreshPermissions() {
        setPermStatus(stOverlay, swOverlay, Settings.canDrawOverlays(this));
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        setPermStatus(stNotify, swNotify, notifOk);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        setPermStatus(stBattery, swBattery, pm.isIgnoringBatteryOptimizations(getPackageName()));
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        setPermStatus(stAdmin, swAdmin, dpm.isAdminActive(new ComponentName(this, AdminReceiver.class)));
    }

    private void setPermStatus(TextView status, Switch sw, boolean granted) {
        setStatus(status, granted);
        sw.setChecked(granted);
    }

    private void setStatus(TextView status, boolean granted) {
        status.setText(granted ? R.string.status_granted : R.string.status_denied);
        status.setTextColor(granted ? 0xFF00E676 : 0xFFFF4D4D);
    }

    // ---------- settings intents ----------

    private void openOverlaySettings() {
        suppressAutoStart = true;
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void openNotificationSettings() {
        suppressAutoStart = true;
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
    }

    private void requestIgnoreBatteryOptimizations() {
        suppressAutoStart = true;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void requestAdmin() {
        suppressAutoStart = true;
        ComponentName cn = new ComponentName(this, AdminReceiver.class);
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.perm_admin_desc));
        startActivity(i);
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }
}
