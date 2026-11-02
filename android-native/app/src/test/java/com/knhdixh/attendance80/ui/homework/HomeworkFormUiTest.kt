package com.knhdixh.attendance80.ui.homework

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.knhdixh.attendance80.logic.Dates
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class HomeworkFormUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun sessionCopiesLocalTimeAndCourseChangeKeepsManualDateAtLargeText() {
        val zone = ZoneId.of("Europe/Paris")
        val now = Instant.parse("2026-03-28T23:30:00Z").toEpochMilli()
        val start = Instant.parse("2026-03-29T09:15:00Z").toEpochMilli()
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()).copy(
            courses = listOf(CourseDto(1, "Course A"), CourseDto(2, "Course B"), CourseDto(3, "Untracked", tracked = false)),
            events = listOf(EventDto(10, 1, "Session", start_at = start, end_at = start + 3600000)))
        var body: JsonObject? = null
        ui.setContent { val d = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
            Attendance80Theme { HomeworkForm(data, null, now, zone, "", {}, { _, value -> body = value; false }, null, {}) }
        } }
        ui.onNodeWithTag("homework-date").assertTextEquals("Due date: ${homeworkDateDisplay(LocalDate.parse("2026-03-30"))}")
        ui.onNodeWithText("What's due").performTextInput("Lab")
        ui.onNodeWithText("Course: No course").performScrollTo().performClick()
        ui.onNodeWithText("Course A").performClick()
        ui.onNodeWithText("Due at a session: — or pick a session —").performScrollTo().performClick()
        ui.onNodeWithText("${Dates.formatDate(start, zone)} ${Dates.formatTime(start, zone)}").performClick()
        ui.onNodeWithTag("homework-date").assertTextEquals("Due date: ${homeworkDateDisplay(LocalDate.parse("2026-03-29"))}")
        ui.onNodeWithTag("homework-time").assertTextEquals("Due time: ${homeworkTimeDisplay(LocalTime.of(11, 15))}")
        ui.onNodeWithTag("homework-date").performScrollTo().performClick()
        ui.runOnIdle {
            val picker = ShadowDialog.getLatestDialog() as DatePickerDialog
            picker.datePicker.updateDate(2026, 3, 1)
            picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        ui.onNodeWithText("Course: Course A").performScrollTo().performClick()
        ui.onNodeWithText("Course B").performClick()
        ui.onAllNodesWithText("Untracked").assertCountEquals(0)
        ui.onNodeWithText("Add homework").performScrollTo().performClick()
        ui.waitUntil(5000) { body != null }
        assertEquals("2026-04-01", body!!.getValue("due_date").jsonPrimitive.content)
        assertEquals("11:15", body!!.getValue("due_time").jsonPrimitive.content)
        assertEquals("Europe/Paris", body!!.getValue("tz").jsonPrimitive.content)
        assertEquals(2, body!!.getValue("course_id").jsonPrimitive.int)
        assertEquals(1440, body!!.getValue("remind_minutes").jsonPrimitive.int)
    }
    @Test fun editingKeepsUntrackedCourseAndNonstandardEffortAndEnforcesLimits() {
        val zone = ZoneId.of("Europe/Paris")
        val item = HomeworkDto(7, course_id = 3, title = "Existing", notes = "Notes", due_date = "2026-10-29", due_at = 1793270000000, effort_minutes = 77)
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()).copy(
            courses = listOf(CourseDto(3, "Untracked", tracked = false)), homework = listOf(item))
        var body: JsonObject? = null
        ui.setContent { Attendance80Theme { HomeworkForm(data, 7, 1793198400000L, zone, "", {}, { _, value -> body = value; false }, null, {}) } }
        ui.onNodeWithText("Course: Untracked").performScrollTo().assertExists()
        ui.onNodeWithText("Time it needs: 77 min").performScrollTo().assertExists()
        ui.onNodeWithText("What's due").performScrollTo().performTextReplacement("x".repeat(210))
        ui.onNodeWithText("Notes").performTextReplacement("n".repeat(4100))
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.waitUntil(5000) { body != null }
        assertEquals(200, body!!.getValue("title").jsonPrimitive.content.length)
        assertEquals(4000, body!!.getValue("notes").jsonPrimitive.content.length)
        assertEquals(77, body!!.getValue("effort_minutes").jsonPrimitive.int)
        assertEquals(3, body!!.getValue("course_id").jsonPrimitive.int)
    }
    @Test fun newFormUsesElapsedDayAtAutumnDstTransition() {
        val zone = ZoneId.of("Europe/Paris")
        val now = Instant.parse("2026-10-24T22:30:00Z").toEpochMilli()
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        ui.setContent { Attendance80Theme { HomeworkForm(data, null, now, zone, "", {}, { _, _ -> false }, null, {}) } }
        ui.onNodeWithTag("homework-date").assertTextEquals("Due date: ${homeworkDateDisplay(LocalDate.parse("2026-10-25"))}")
    }
    @Test fun dateAndTimeUseLocaleWithoutShiftingCivilDate() {
        val date = LocalDate.of(2026, 3, 29)
        assertEquals("Mar 29, 2026", homeworkDateDisplay(date, java.util.Locale.US))
        assertEquals("29 mars 2026", homeworkDateDisplay(date, java.util.Locale.FRANCE))
        assertEquals("1:15 PM", homeworkTimeDisplay(LocalTime.of(13, 15), java.util.Locale.US))
        assertEquals("13:15", homeworkTimeDisplay(LocalTime.of(13, 15), java.util.Locale.FRANCE))
        assertNotEquals(homeworkDayDisplay(date, java.util.Locale.US), homeworkDayDisplay(date, java.util.Locale.FRANCE))
        assertTrue(homeworkDayDisplay(date, java.util.Locale.US).contains("29"))
        assertTrue(homeworkDayDisplay(date, java.util.Locale.FRANCE).contains("29"))
    }
    @Test fun timePickerAndClearSubmitExplicitNull() {
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        var body: JsonObject? = null
        ui.setContent { Attendance80Theme { HomeworkForm(data, null, 1793198400000L, ZoneId.of("Europe/Paris"), "", {}, { _, value -> body = value; false }, null, {}) } }
        ui.onNodeWithTag("homework-time").performScrollTo().performClick()
        ui.runOnIdle {
            val picker = ShadowDialog.getLatestDialog() as TimePickerDialog
            picker.updateTime(21, 45)
            picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        ui.onNodeWithTag("homework-time").assertTextEquals("Due time: ${homeworkTimeDisplay(LocalTime.of(21, 45))}")
        ui.onNodeWithText("Clear time").performScrollTo().performClick()
        ui.onNodeWithTag("homework-time").assertTextEquals("Due time: No time")
        ui.onNodeWithText("Add homework").performScrollTo().performClick()
        ui.waitUntil(5000) { body != null }
        assertEquals(JsonNull, body!!["due_time"])
    }
}
