package com.knhdixh.attendance80.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.ui.settings.MAX_ICS_BYTES
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val resolver get() = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n"
    private fun file(name: String, text: String = ics) = tmp.newFile(name).also { it.writeText(text) }

    @Test fun openingAnIcsFileGivesItsNameAndText() {
        val f = file("timetable.ics")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "text/calendar")
        assertEquals(Incoming.CalendarFile("timetable.ics", ics), readIncoming(intent, resolver))
    }

    @Test fun anIcsNameWithAGenericTypeIsStillACalendar() {
        val f = file("EPITA S3.ics")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "application/octet-stream")
        assertEquals("EPITA S3.ics", (readIncoming(intent, resolver) as Incoming.CalendarFile).name)
    }

    @Test fun otherFilesAndTheAppsOwnLinksAreNotOurs() {
        assertNull(readIncoming(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file("notes.txt")), "text/plain"), resolver))
        assertNull(readIncoming(Intent(Intent.ACTION_VIEW, Uri.parse("a80://today")), resolver))
        assertNull(readIncoming(Intent(Intent.ACTION_VIEW, Uri.parse("com.knhdixh.attendance80:/auth?code=x")), resolver))
        assertNull(readIncoming(Intent(Intent.ACTION_MAIN), resolver))
    }

    @Test fun sharingACalendarFileGivesItsText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/calendar").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file("a.ics")))
        assertEquals(Incoming.CalendarFile("a.ics", ics), readIncoming(intent, resolver))
    }

    @Test fun anIcsSharedAsPlainTextIsStillACalendarNotHomework() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file("a.ics")))
        assertTrue(readIncoming(intent, resolver) is Incoming.CalendarFile)
    }

    @Test fun sharedTextIsHomeworkText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Read chapter 3\nPages 10-20")
        assertEquals(Incoming.SharedText("Read chapter 3\nPages 10-20"), readIncoming(intent, resolver))
    }

    @Test fun blankTextAndOtherTypesAreIgnored() {
        assertNull(readIncoming(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  \n"), resolver))
        assertNull(readIncoming(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "hello"), resolver))
    }

    @Test fun aFileOverFiveMegabytesAndAnUnreadableOneGiveAnErrorAndNoText() {
        val big = file("big.ics", "x".repeat(MAX_ICS_BYTES + 1))
        val tooBig = readIncoming(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(big), "text/calendar"), resolver) as Incoming.CalendarFile
        assertNull(tooBig.text)
        assertEquals("That file is over 5 MB.", tooBig.error)
        val gone = readIncoming(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(tmp.root.resolve("gone.ics")), "text/calendar"), resolver) as Incoming.CalendarFile
        assertNull(gone.text)
        assertEquals("Could not read that file.", gone.error)
    }

    @Test fun firstLineIsTheTitleAndTheRestTheNotes() {
        assertEquals("Read chapter 3" to "Pages 10-20\nQuiz on Friday", splitSharedText("Read chapter 3\nPages 10-20\nQuiz on Friday\n"))
        assertEquals("Just a title" to "", splitSharedText("  Just a title  "))
        assertEquals("Title" to "Notes", splitSharedText("\n\nTitle\n\nNotes"))
        assertEquals("" to "", splitSharedText(""))
    }

    @Test fun aFirstLineLongerThanTheTitleFieldKeepsItsOverflowInTheNotes() {
        val (title, notes) = splitSharedText("a".repeat(250) + "\nmore")
        assertEquals(TITLE_MAX, title.length)
        assertEquals("a".repeat(50) + "\nmore", notes)
        assertEquals(NOTES_MAX, splitSharedText("t\n" + "n".repeat(NOTES_MAX + 10)).second.length)
    }
}
