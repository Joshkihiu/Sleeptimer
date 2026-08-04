# Screen Off Timer

A sleek, native-feeling Android utility designed to turn off your screen and lock your device based on time, exact clock hours, or battery drain. Built with a dark, minimalist aesthetic and an intuitive floating picture-in-picture (PiP) widget.

## Download & Install

Grab the **release-signed APK** straight from this repo and sideload it:

**Download:** [`SleepTimer.apk`](./SleepTimer.apk)

```bash
# or via adb
adb install SleepTimer.apk
```

The APK is signed with a proper release certificate (not a debug key), so Google Play Protect won't flag it as potentially harmful. After installing:
1. Open the app and grant the requested permissions.
2. Enable **Device Admin** (Settings → Device Admin) so the screen can auto-lock at zero.
3. Scroll a mode and leave the app — the timer starts automatically.

## Core Features

The app features three independent triggers. Whichever threshold is reached first will trigger the screen off/device lock event.

* ** Timer Mode:** A standard duration countdown (1 - 120 minutes). 
* ** Sleep At Mode:** An exact target time selector (Hours and Minutes). Perfect for setting a hard cutoff time at night.
* ** Battery Mode (Kill-Switch):** A dynamic scroll wheel that live-tracks your current battery percentage. Sets a hard floor (e.g., 15%) so you never wake up to a dead phone if you fall asleep watching a stream.

##  UI & Layout

The main app view utilizes a strict **30/50/20** vertical flex layout to prioritize touch targets based on usage frequency:
* **Top (30%):** Timer Mode
* **Middle (50%):** Sleep At Mode (Larger touch area for dual-wheel precision)
* **Bottom (20%):** Battery Mode (Inactive/dimmed by default to prevent accidental triggers; wakes on touch)

*Note: All scroll wheels feature a synthesized haptic audio "tick" when snapping to a new number to mimic native mechanical components.*

##  The Floating Widget (Overlay)

When the timer is started (via the System Home button), the app drops into the background and spawns a draggable floating pill widget.

### Gestures & Interactions
* **Drag & Drop:** Single press and hold to move the widget anywhere on the screen.
* **Ghost Mode (Auto-Dim):** If untouched for 5 seconds, the widget drops to 15% opacity so it doesn't obstruct videos or games. Touching it instantly wakes it up.
* **Extend Time (Double-Tap & Slide):** 
  * **Double-tap and hold** the widget to enter *Extend Mode*.
  * The widget will drop a "shell" showing the original time.
  * **Drag to the right** to dynamically add time to the running clock (+1 minute per 10 pixels dragged).
  * Release to snap back and commit the new time.

## App Icon Generator

Tap the palette icon in the header to open the built-in icon studio. It generates the app icon **entirely on-device** with the canvas API — no external assets or image files needed.

* **Design:** A dark rounded-square launcher tile with a soft glow, a clock face ring (with subtle 12/3/6/9 ticks), a crescent moon, and a timer hand pointing at 12 o'clock — matching the app's dark, minimalist sleep-timer aesthetic.
* **Themes:** Midnight (default green accent), Ink (pure black/white), Aurora (blue), and Ember (orange).
* **Export:** Download as PNG at 256, 512, or 1024 px — ready to drop into a native Android `mipmap` set.
* **Bonus:** The generated icon is automatically set as the page favicon on load, and you can re-apply it from the studio with one tap.

## � Native Android App (`android/`)

The idea from this README is now implemented as a **real, installed Android app** (Java, built with Gradle) living in the `android/` folder:

* **Modes:** Timer (1–120 min), Sleep At (HH:MM), and Battery kill-switch (1–100%) — same three triggers as the prototype. Battery mode shows a **live percentage countdown** (e.g. `65%`) on the widget and notification until the floor is hit.
* **Auto-start on leave:** there is no Start button — simply scroll a mode and leave the app (Home / app switch); the timer starts automatically, exactly like the prototype's system-Home button. The three modes (Timer / Sleep At / Battery) are mutually exclusive — selecting one dims the others. The header ▶ play button starts/stops it manually, and **Settings** has a *Start timer on exit* toggle (default ON).
* **Foreground service** (`TimerService`) runs the countdown with a live notification (with a Stop action) and battery check.
* **Floating widget** (`OverlayView`) — draggable pill shown over other apps; drag to move, double-tap + drag right to extend time (+1 min / 10px), ghost-dims after 5s idle.
* **Screen-off** via `DevicePolicyManager.lockNow()` when time is up or the battery floor is hit (no root). If Device Admin isn't active, the app posts a notification explaining why the screen couldn't lock.
* **Permissions panel** in-app: Display Over Apps, Notifications, Ignore Battery Optimization, and Device Admin, each with live status + shortcut. There's no forced onboarding — the app opens straight on the timer; if a permission is missing when you press ▶, a small dialog asks you to grant it (with a "Start anyway" option).
* **App icon** generated by `android/tools/make_icon.py` (PIL) — same moon/ring/hand design, shipped as legacy PNGs + adaptive icon + notification glyph.

### Build & Install

Uses the same toolchain as the UShare project (Gradle wrapper 8.10 + JDK 21 + AGP 8.5.2, SDK at `/usr/lib/android-sdk`):

```bash
cd android
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/SleepTimer-debug.apk
adb shell am start -n com.thetimep.screentimer/.MainActivity
```

Optional adb shortcuts (post-install):

```bash
adb shell appops set com.thetimep.screentimer SYSTEM_ALERT_WINDOW allow   # overlay
adb shell dumpsys deviceidle whitelist +com.thetimep.screentimer          # battery opt-out
```

Device Admin is granted from the app (Permissions → Device Admin) so the screen can be force-locked at zero.

## � Required Android Permissions

To fully implement this in a native Android environment (Kotlin/Java or Flutter/React Native), the following permissions are mapped out in the Settings menu:

1. **Display Over Other Apps (`SYSTEM_ALERT_WINDOW`):** Required to spawn the draggable floating timer over YouTube, Netflix, or games.
2. **Device Admin (`BIND_DEVICE_ADMIN`):** Required to actively force the screen to turn off and lock the device when the timer hits zero without requiring root access.
3. **Ignore Battery Optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`):** Prevents the Android OS from killing the background timer service while the user is passively watching content.

##  Development Notes

* **Current Status:** Native Android app (Java/Gradle) is built and installed on-device; the HTML/CSS/JS file (`index.html`) remains as the high-fidelity design reference.
* **New:** In-app App Icon Generator in the web prototype (canvas-based, theme presets, PNG export, auto-favicon).
* **Implemented natively:** DevicePolicyManager screen-off, background foreground-service countdown, floating overlay widget, battery kill-switch.
* **Next Steps:** 
  * Re-run battery trigger periodically even when idle (currently checked once per second in the service tick).
  * Add a boot-completed receiver to resume timers after a reboot.
  * Tune the widget gestures for edge-to-edge Android 15/16 display cutouts.
