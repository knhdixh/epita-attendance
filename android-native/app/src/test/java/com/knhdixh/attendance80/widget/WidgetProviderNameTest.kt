package com.knhdixh.attendance80.widget

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TWA's widget provider name must keep resolving, or an update over the TWA drops the user's placed widget. */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WidgetProviderNameTest {
    @Test fun twaProviderNameIsAReceiverWithWidgetMetadata() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getReceiverInfo(
            ComponentName("com.knhdixh.attendance80", "com.knhdixh.attendance80.FocusWidget"), PackageManager.GET_META_DATA)
        assertNotNull(info.metaData?.get("android.appwidget.provider"))
    }
}
