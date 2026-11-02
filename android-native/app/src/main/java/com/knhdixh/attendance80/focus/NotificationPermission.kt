package com.knhdixh.attendance80.focus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android 13+ needs a runtime permission before the lock-screen countdown can show. */
fun notificationsMissing(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Calls [ask] the first time only, and only if the permission is missing. Never at launch. */
suspend fun askNotificationsOnce(context: Context, store: FocusStore, ask: () -> Unit) {
    if (!notificationsMissing(context) || store.notificationsAsked()) return
    store.markNotificationsAsked()
    ask()
}
