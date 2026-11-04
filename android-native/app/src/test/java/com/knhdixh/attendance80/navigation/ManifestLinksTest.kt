package com.knhdixh.attendance80.navigation

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.knhdixh.attendance80.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestLinksTest {
    @Test fun filtersResolveAndShortcutsPointAtInternalRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (route in listOf("today", "focus", "homework", "homework/new", "session/1", "course/2", "add", "inbox", "project/3", "filter/4")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("a80://$route")).addCategory(Intent.CATEGORY_BROWSABLE)
            assertNotNull(route, context.packageManager.resolveActivity(intent, 0))
        }
        val parser = context.resources.getXml(R.xml.shortcuts)
        val links = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                links += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "data")
            }
        }
        assertEquals(listOf("a80://focus", "a80://today", "a80://homework", "a80://add"), links)
        links.forEach { assertNotNull(it, internalRoute(it)) }
    }
}
