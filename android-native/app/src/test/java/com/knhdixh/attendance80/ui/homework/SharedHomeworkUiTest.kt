package com.knhdixh.attendance80.ui.homework

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** Text shared into the app: the first line is the title, the rest the notes (plan 7.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SharedHomeworkUiTest {
    @get:Rule val ui = createComposeRule()
    private val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())

    @Test fun sharedTextFillsTitleAndNotesAndSavesThem() {
        var body: JsonObject? = null
        ui.setContent { Attendance80Theme { HomeworkForm(data, null, 1793198400000L, ZoneId.of("Europe/Paris"), "Read chapter 3\nPages 10-20\nQuiz on Friday", {}, { _, v -> body = v; false }, null, {}) } }
        ui.onNodeWithText("What's due").assertTextContains("Read chapter 3")
        ui.onNodeWithText("Notes").assertTextContains("Pages 10-20\nQuiz on Friday")
        ui.onNodeWithText("Add homework").performScrollTo().performClick()
        ui.waitUntil(5000) { body != null }
        assertEquals("Read chapter 3", body!!.getValue("title").jsonPrimitive.content)
        assertEquals("Pages 10-20\nQuiz on Friday", body!!.getValue("notes").jsonPrimitive.content)
    }

    @Test fun aOneLineSearchQueryStaysTheTitleAndLeavesNotesEmpty() {
        var body: JsonObject? = null
        ui.setContent { Attendance80Theme { HomeworkForm(data, null, 1793198400000L, ZoneId.of("Europe/Paris"), "Lab report", {}, { _, v -> body = v; false }, null, {}) } }
        ui.onNodeWithText("Add homework").performScrollTo().performClick()
        ui.waitUntil(5000) { body != null }
        assertEquals("Lab report", body!!.getValue("title").jsonPrimitive.content)
        assertEquals("", body!!.getValue("notes").jsonPrimitive.content)
    }
}
