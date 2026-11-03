package com.knhdixh.attendance80.focus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android 13+ needs a runtime permission before the lock-screen countdown can show. */
fun notificationsMissing(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Calls [ask] the first time only, and only if the permission is missing. Never at launch. True if it asked. */
suspend fun askNotificationsOnce(context: Context, store: FocusStore, ask: () -> Unit): Boolean {
    if (!notificationsMissing(context) || store.notificationsAsked()) return false
    store.markNotificationsAsked()
    ask()
    return true
}

/**
 * Closes the quick-focus sheet after a command, except while the permission dialog is open: the sheet then stays until
 * the answer arrives, so the answer can redraw the timer notification (and is not lost with a finished activity).
 */
class FinishGate(private val finish: () -> Unit) {
    private var asking = false
    private var wanted = false
    fun asked() { asking = true }
    /** The command has run: close now, or as soon as the permission answer arrives. */
    fun commandDone() { if (asking) wanted = true else finish() }
    fun answered() { asking = false; if (wanted) { wanted = false; finish() } }
}

/**
 * Whether the system will still show the permission dialog. It will before the first ask, and again after one denial
 * (the system wants a reason); after two denials it never does, and only the system settings can turn it on.
 */
fun canAskNotifications(missing: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): Boolean =
    missing && (!askedBefore || shouldShowRationale)
