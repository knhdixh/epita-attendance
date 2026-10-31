package com.knhdixh.attendance80.ui.course

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.robolectric.Shadows.shadowOf
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import com.knhdixh.attendance80.ui.search.PaletteAction
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class CourseTest {
    @get:Rule val ui = createComposeRule()
    private val now = 1793600000000L
    private val zone = ZoneId.of("Europe/Paris")
    private fun data(): AppData {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        return base.copy(courses = listOf(CourseDto(1, "GR1 - Language", tracked = false, attendance = false), CourseDto(2, "GR2 - Language")),
            events = listOf(EventDto(11, 1, "Past exam", start_at = now - 3600000, end_at = now - 1, status = "absent", tag = "exam"), EventDto(12, 1, "Future class", start_at = now + 3600000, end_at = now + 7200000)))
    }
    @Test fun deriveMissingAndUntrackedScopedStats() {
        val d = data()
        assertNull(CourseViewModel.derive(d, 99, now, zone).course)
        val state = CourseViewModel.derive(d, 1, now, zone)
        assertEquals(2, state.events.size)
        assertEquals(1, state.stats!!.missedMustAttend.size)
        assertEquals(listOf(1L, 2L), state.family!!.courses.map { it.id })
        assertEquals(1, CourseViewModel.derive(d.copy(events = d.events.take(1)), 1, now, zone).events.size)
    }
    @Test fun viewModelPublishesPinnedMinuteAndClears() {
        val model = CourseViewModel()
        val d = data()
        ui.runOnIdle { model.update(d, 1, now + 1234, zone) }
        ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); model.state.value != null }
        assertEquals(now / 60000 * 60000, model.state.value!!.now)
        assertEquals(zone, model.state.value!!.zone)
        assertEquals(2, model.state.value!!.events.size)
        ui.runOnIdle { model.update(d.copy(events = emptyList()), 1, now, zone) }
        ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); model.state.value?.events?.isEmpty() == true }
        ui.runOnIdle { model.update(null, 1, now, zone) }
        assertNull(model.state.value)
    }
    @Test fun shellEqualSnapshotsChangeAndAccountReset() {
        val initial = data()
        var state by mutableStateOf(ShellState(data = initial, session = "a"))
        ui.setContent { Attendance80Theme { Shell(state, {}, {}, {}, initialRoute = "course/1", now = now, zone = zone) } }
        ui.waitUntil(10000) { ui.onAllNodesWithText("GR1 - Language").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { state = state.copy(data = initial.copy(), loading = true) }
        ui.onNodeWithText("GR1 - Language").assertIsDisplayed()
        ui.runOnIdle { state = state.copy(data = initial.copy(courses = initial.courses.map { if (it.id == 1L) it.copy(name = "Changed") else it })) }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Changed").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { state = ShellState(data = null) }
        ui.onAllNodesWithText("Changed").assertCountEquals(0)
        ui.runOnIdle { state = ShellState(data = initial.copy(courses = emptyList()), session = "b") }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Course not found.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Back").assertExists()
    }
    @Test fun productionControlsDispatchAndSessionsNavigate() {
        var action: PaletteAction? = null
        var route = ""
        ui.setContent { Attendance80Theme { CourseScreen(data(), null, 1, now, zone, { route = it }, {}, { action = it }) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("course-detail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("course-tracked").performClick()
        ui.runOnIdle { assertEquals(PaletteAction.CourseField(1, "tracked", "true"), action) }
        ui.onNodeWithText("Default tag: Normal").performClick()
        ui.onNode(hasText("Exam") and hasAnyAncestor(isDialog())).performClick()
        ui.runOnIdle { assertEquals(PaletteAction.CourseField(1, "tag", "exam"), action) }
        ui.onNodeWithTag("course-detail").performScrollToNode(hasText("GR2"))
        ui.onNodeWithText("GR2").performClick()
        ui.onNodeWithText("Not in this course").performClick()
        ui.runOnIdle { assertEquals(PaletteAction.CourseGroups(listOf(1, 2), null), action) }
        ui.onNodeWithTag("course-detail").performScrollToNode(hasText("Future class"))
        ui.onNodeWithText("Future class").performClick()
        ui.runOnIdle { assertEquals("session/12", route) }
    }
    @Test fun recordingFormSendsEditedAndEmptyUrl() {
        var action: PaletteAction? = null
        val d = data().copy(courses = data().courses.map { if (it.id == 1L) it.copy(recording_url = "https://example.org/old") else it })
        ui.setContent { Attendance80Theme { Shell(ShellState(data = d), {}, {}, {}, initialRoute = "course/1", now = now, zone = zone, onAction = { action = it }) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("course-detail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("course-detail").performScrollToNode(hasText("Edit link"))
        ui.mainClock.autoAdvance = false
        ui.onNodeWithText("Edit link").performClick()
        ui.mainClock.advanceTimeBy(1000)
        ui.onNodeWithTag("recording-url").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("https://example.org/new")) }
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle { assertEquals(PaletteAction.CourseField(1, "recording_url", "https://example.org/new"), action) }
        ui.onNodeWithText("Edit link").performClick()
        ui.mainClock.advanceTimeBy(1000)
        ui.onNodeWithTag("recording-url").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("")) }
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle { assertEquals(PaletteAction.CourseField(1, "recording_url", ""), action) }
    }
    @Test fun recordingOpensExternalViewIntent() {
        val d = data().copy(courses = data().courses.map { if (it.id == 1L) it.copy(recording_url = "https://example.org/recordings") else it })
        ui.setContent { Attendance80Theme { Shell(ShellState(data = d), {}, {}, {}, initialRoute = "course/1", now = now, zone = zone) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("course-detail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("course-detail").performScrollToNode(hasText("Open recordings ↗"))
        ui.onNodeWithText("Open recordings ↗").performClick()
        val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/recordings", intent.data.toString())
    }
}
