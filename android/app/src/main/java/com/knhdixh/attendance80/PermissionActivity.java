package com.knhdixh.attendance80;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Asks for the notification permission (Android 13+) the first time a focus starts, so the lock-screen countdown
 * can show. A see-through activity that closes once the system dialog is answered; asked at most once.
 */
public class PermissionActivity extends Activity {
    private static final String ASKED = "notifications_asked";

    static void askOnce(Context c) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = c.getSharedPreferences("focus", MODE_PRIVATE);
        if (p.getBoolean(ASKED, false)) return;
        p.edit().putBoolean(ASKED, true).apply();
        c.startActivity(new Intent(c, PermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        else finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        FocusController.apply(this, new FocusStore(this).timer(), null, true); // shows the timer if it's running
        finish();
    }
}
