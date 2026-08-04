package com.thetimep.screentimer;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device Admin receiver that lets the app force the screen off / lock
 * when the timer hits zero (no root required).
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
