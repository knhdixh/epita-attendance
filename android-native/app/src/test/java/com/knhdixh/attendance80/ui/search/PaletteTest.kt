package com.knhdixh.attendance80.ui.search

import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class PaletteTest {
    private val data = screenshotData()
    @Test fun searchesEachEntityAndRoutesToItsItem() {
        for ((query, route) in listOf("mathematiques" to "course/1", "amphi" to "session/1", "exercise" to "homework/1", "read" to "habits/1", "exam" to "countdowns/1")) {
            assertTrue(query, searchPalette(data, query).any { it.route == route })
        }
        assertTrue(searchPalette(data, "xyzzy").any { it.action is PaletteAction.SessionSearch })
    }
    @Test fun oneSessionPerTrackedSummaryChoosesNextOrLast() {
        val event = data.events.first()
        val events = listOf(event.copy(id = 21, start_at = 100, end_at = 150),
            event.copy(id = 22, start_at = 200, end_at = 250),
            event.copy(id = 23, start_at = 300, end_at = 350),
            event.copy(id = 24, course_id = 999, start_at = 400, end_at = 450))
        val fixture = data.copy(events = events)
        assertEquals(listOf("session/22"), paletteItems(fixture, 175).filter { it.section == "Sessions" }.map { it.route })
        assertEquals(listOf("session/23"), paletteItems(fixture, 500).filter { it.section == "Sessions" }.map { it.route })
    }
    @Test fun recentsAndNaturalLanguageFallbackKeepTheirPayloads() {
        val recent = searchPalette(data, "", listOf("nav:focus", "nav:today"), 1793600000000)
        assertEquals(listOf("nav:focus", "nav:today"), recent.filter { it.section == "Recent" }.map { it.id })
        val habit = searchPalette(data, "Read 3x a week", now = 1793600000000).single { it.id == "fallback:habit" }
        assertEquals(PaletteAction.AddHabit("Read 3x a week"), habit.action)
        val countdown = searchPalette(data, "Exam tomorrow", now = 1793600000000).single { it.id == "fallback:countdown" }
        assertEquals(PaletteAction.AddCountdown("Exam tomorrow"), countdown.action)
        assertEquals(PaletteAction.SessionSearch("algebra"), searchPalette(data, "algebra").single { it.id == "fallback:sessions" }.action)
    }
    @Test fun formAndFocusRequestsRetainIdsAndTextWhenSaved() {
        val actions = listOf<PaletteAction>(
            paletteItems(data).first { it.id == "homework:1" }.action!!,
            PaletteAction.Focus("target", homeworkId = 9, task = "revision"),
            PaletteAction.OpenForm("calendar", "webcal://example.invalid"))
        assertEquals(PaletteAction.OpenForm("homework-edit", entityId = 1), actions.first())
        actions.forEach { action ->
            val saved = ApiClient.json.encodeToString(action)
            assertEquals(action, ApiClient.json.decodeFromString<PaletteAction>(saved))
        }
    }
    @Test fun homeActionsAndScoringMatchPalette() {
        assertTrue(searchPalette(data, "").any { it.label == "Add homework" })
        assertTrue(searchPalette(data, "log out").any { it.id == "logout" })
        assertTrue(searchPalette(data, "routine").any { it.label == "New habit" })
        assertTrue(searchPalette(null, "").isEmpty())
    }
}
