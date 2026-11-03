package com.knhdixh.attendance80.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestDeclarationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val info get() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

    @Test fun liveUpdatesNeedThePromotedNotificationsPermission() {
        assertTrue(info.requestedPermissions!!.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
    }
    @Test fun theReminderReceiverReplansWhenExactAlarmsAreGrantedOrRevoked() {
        val intent = Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED").setPackage(context.packageName)
        assertTrue(context.packageManager.queryBroadcastReceivers(intent, 0).any { it.activityInfo.name.endsWith("NotifyReceiver") })
    }
    @Test fun predictiveBackIsOptedIn() {
        // Read the manifest text itself: the application element carries enableOnBackInvokedCallback="true".
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:enableOnBackInvokedCallback="true""""))
    }
}
