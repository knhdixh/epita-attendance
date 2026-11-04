package com.knhdixh.attendance80

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 13, task 3: the tile, the launcher shortcut and shared text all end up at the same quick add link. */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class QuickAddSurfacesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun theTileOpensTheAddLinkInTheMainActivity() {
        val i = quickAddIntent(context)
        assertEquals("a80://add", i.dataString); assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(MainActivity::class.java.name, i.component!!.className)
        assertEquals("add", com.knhdixh.attendance80.navigation.internalRoute(i.dataString!!))
    }

    @Test fun theTileServiceIsRegisteredAsAQuickSettingsTile() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, QuickAddTileService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission); assertTrue(info.exported)
        val handlers = context.packageManager.queryIntentServices(Intent("android.service.quicksettings.action.QS_TILE").setPackage(context.packageName), 0)
        assertTrue(handlers.any { it.serviceInfo.name == QuickAddTileService::class.java.name })
    }

    @Test fun theLauncherShortcutAndTheManifestAgreeWithTheLink() {
        val shortcuts = context.resources.getXml(context.resources.getIdentifier("shortcuts", "xml", context.packageName))
        val data = mutableListOf<String>(); val ids = mutableListOf<String>()
        while (shortcuts.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (shortcuts.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (shortcuts.name == "shortcut") ids += shortcuts.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutId")
            if (shortcuts.name == "intent") data += shortcuts.getAttributeValue("http://schemas.android.com/apk/res/android", "data")
        }
        assertTrue(ids.toString(), "add-task" in ids); assertTrue(data.toString(), "a80://add" in data)
        // Shared text is accepted by the activity (the intent filter), and the viewer of the link resolves to it.
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(send, 0).any { it.activityInfo.name == MainActivity::class.java.name })
        assertTrue(context.packageManager.queryIntentActivities(quickAddIntent(context).setComponent(null).setPackage(context.packageName), 0).isNotEmpty())
    }
}
