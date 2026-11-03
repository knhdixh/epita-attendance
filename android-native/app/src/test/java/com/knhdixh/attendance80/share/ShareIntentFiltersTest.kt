package com.knhdixh.attendance80.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The manifest's filters, as the system resolves them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntentFiltersTest {
    private val pm get() = ApplicationProvider.getApplicationContext<Context>().packageManager
    private fun resolves(intent: Intent) = pm.resolveActivity(intent, 0)?.activityInfo?.name == "com.knhdixh.attendance80.MainActivity"
    private fun view(uri: String, type: String?) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type).addCategory(Intent.CATEGORY_BROWSABLE)

    @Test fun calendarFilesOpenTheApp() {
        assertTrue(resolves(view("content://downloads/public/1", "text/calendar")))
        assertTrue(resolves(view("file:///sdcard/Download/timetable.ics", "text/calendar")))
        assertTrue(resolves(view("content://com.android.providers.downloads.documents/document/timetable.ics", "application/octet-stream")))
        assertTrue(resolves(view("content://com.example.mail/attachments/S3.final.ics", "application/octet-stream")))
    }

    @Test fun otherFilesDoNot() {
        assertFalse(resolves(view("content://downloads/public/2", "application/pdf")))
        assertFalse(resolves(view("content://com.example.mail/attachments/notes.txt", "application/octet-stream")))
    }

    @Test fun sendingTextOrACalendarOpensTheApp() {
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType("text/calendar")))
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("image/png")))
    }
}
