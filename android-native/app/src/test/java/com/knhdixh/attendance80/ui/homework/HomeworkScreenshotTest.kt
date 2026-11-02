package com.knhdixh.attendance80.ui.homework

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File

abstract class HomeworkScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightMain() = capture(false, 1f, "main")
    @Test fun darkMain() = capture(true, 1f, "main")
    @Test fun lightMain2() = capture(false, 2f, "main")
    @Test fun darkMain2() = capture(true, 2f, "main")
    @Test fun lightEmpty() = capture(false, 1f, "empty")
    @Test fun darkEmpty() = capture(true, 1f, "empty")
    @Test fun lightAdd() = capture(false, 1f, "add")
    @Test fun darkAdd() = capture(true, 1f, "add")
    @Test fun lightAdd2() = capture(false, 2f, "add")
    @Test fun darkAdd2() = capture(true, 2f, "add")
    @Test fun lightAddBottom2() = capture(false, 2f, "addBottom")
    @Test fun darkAddBottom2() = capture(true, 2f, "addBottom")
    @Test fun lightEdit() = capture(false, 1f, "edit")
    @Test fun darkEdit() = capture(true, 1f, "edit")
    @Test fun lightEditBottom2() = capture(false, 2f, "editBottom")
    @Test fun darkEditBottom2() = capture(true, 2f, "editBottom")
    @Test fun lightDone() = capture(false, 1f, "done")
    @Test fun darkDone() = capture(true, 1f, "done")
    @Test fun lightDelete() = capture(false, 1f, "delete")
    @Test fun darkDelete() = capture(true, 1f, "delete")
    @Test fun lightDelete2() = capture(false, 2f, "delete")
    @Test fun darkDelete2() = capture(true, 2f, "delete")
    @Test fun lightError() = capture(false, 1f, "error")
    @Test fun darkError() = capture(true, 1f, "error")
    @Test fun lightDatePicker() = picker(false, true)
    @Test fun darkDatePicker() = picker(true, true)
    @Test fun lightTimePicker() = picker(false, false)
    @Test fun darkTimePicker() = picker(true, false)
    private fun picker(dark: Boolean, date: Boolean) {
        val data = screenshotData()
        ui.setContent { Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = "homework/new", now = 1793600000000L, zone = ZoneId.of("Europe/Paris")) } }
        ui.onNodeWithTag(if (date) "homework-date" else "homework-time").performScrollTo().performClick()
        ui.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            assertTrue("Picker must be laid out", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val name = "${device}_${if (dark) "dark" else "light"}_homework_${if (date) "datePicker" else "timePicker"}_1.png"
            File("/tmp/epita-native-night/$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val baseline = File("src/test/screenshots/$name")
            assertTrue("Picker capture must match inspected baseline", baseline.exists() && bitmap.sameAs(BitmapFactory.decodeFile(baseline.path)))
        }
    }
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val now = 1793600000000L
        val base = screenshotData()
        val homework = listOf(
            HomeworkDto(900, base.courses.firstOrNull()?.id, "Review a long lecture and prepare the report", notes = "Read the notes and check the example.", due_date = "2026-11-02", due_time = "15:00", due_at = now + 3600000, remind_minutes = 60, kind = "catchup"),
            HomeworkDto(901, title = "Completed assignment", due_date = "2026-11-01", due_at = now - 1000, done = true))
        val data = base.copy(homework = if (kind == "empty") emptyList() else homework)
        val route = when (kind) { "add", "addBottom" -> "homework/new"; "edit", "editBottom" -> "homework/900"; else -> "homework" }
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test", error = if (kind == "error") "Could not save homework" else null), {}, {}, {}, initialRoute = route, now = now, zone = ZoneId.of("Europe/Paris")) }
        } }
        if (route == "homework") ui.waitUntil(10000) { ui.onAllNodesWithTag("homework-list").fetchSemanticsNodes().isNotEmpty() }
        else ui.waitUntil(10000) { ui.onAllNodesWithText("What's due").fetchSemanticsNodes().isNotEmpty() }
        if (kind == "done") { ui.onNodeWithTag("homework-list").performScrollToNode(hasText("Done 1 ▾")); ui.onNodeWithText("Done 1 ▾").performClick(); ui.onNodeWithTag("homework-list").performScrollToNode(hasText("Completed assignment")) }
        if (kind == "delete") { ui.onNodeWithText("Delete").performScrollTo().performClick(); ui.waitUntil(10000) { ui.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() } }
        if (kind == "addBottom" || kind == "editBottom") ui.onNode(hasText(if (kind == "addBottom") "Add homework" else "Save") and hasClickAction()).performScrollTo()
        if (kind == "error") ui.waitUntil(10000) { ui.onAllNodesWithText("Could not save homework").fetchSemanticsNodes().isNotEmpty() }
        val target = if (kind == "delete") ui.onNode(isDialog()) else ui.onRoot()
        target.captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_homework_${kind}_${scale.toInt()}.png")
    }
}
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneHomeworkScreenshotTest : HomeworkScreenshotTest() { override val device = "phone411x891" }
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletHomeworkScreenshotTest : HomeworkScreenshotTest() { override val device = "tablet840x1000" }
