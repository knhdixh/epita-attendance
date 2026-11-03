package com.knhdixh.attendance80.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class WidgetTextTest {
    private val zone = ZoneId.of("UTC")
    private val now = 1_000_000_000_000L
    private val min = 60_000L
    private fun text(s: WidgetSnapshot, at: Long = now) =
        widgetText(s, at, zone, time = { "T${it / min % 1000}" }, day = { "Tue" }, today = { it - now < 6 * 3_600_000 })

    private val math = WidgetClass(1, "Maths", now + 30 * min, now + 90 * min, "Amphi 1")

    @Test fun nextClassToday() {
        val t = text(WidgetSnapshot(signedIn = true, classes = listOf(math)))
        assertEquals("Next class", t.classHead); assertEquals("Maths", t.className)
        assertEquals("Amphi 1 · in 30 min", t.classWhere)
    }

    @Test fun inTheClass() {
        val t = text(WidgetSnapshot(signedIn = true, classes = listOf(math)), now + 40 * min)
        assertEquals("Now", t.classHead); assertEquals("", t.classWhere.substringAfter("Amphi 1").trim(' ', '·'))
        assertEquals("until T${(now + 90 * min) / min % 1000}", t.classTime)
    }

    @Test fun anotherDayNamesTheDay() {
        val far = math.copy(start = now + 30 * 3_600_000, end = now + 31 * 3_600_000)
        assertEquals("Next class · Tue", text(WidgetSnapshot(signedIn = true, classes = listOf(far))).classHead)
    }

    @Test fun noClassesAndSignedOut() {
        assertEquals("No more classes this week", text(WidgetSnapshot(signedIn = true)).className)
        assertEquals("Open the app to sign in", text(WidgetSnapshot()).className)
    }

    @Test fun doNextLinesAndOverdue() {
        val t = text(WidgetSnapshot(signedIn = true, todo = listOf(WidgetTodo(1, "A", now - 1), WidgetTodo(2, "B", now + 1), WidgetTodo(3, "C"))))
        assertEquals(listOf("• A  ·  overdue", "• B  ·  Tue", "• C"), t.todo)
        assertEquals(listOf("Nothing due in the next 7 days."), text(WidgetSnapshot(signedIn = true)).todo)
    }

    @Test fun timerButtonsFollowTheState() {
        assertEquals("Start", text(WidgetSnapshot()).button)
        assertEquals("25 min", text(WidgetSnapshot()).timerLine)
        val running = WidgetSnapshot(timer = WidgetTimer(phase = "focus", running = true, endsAt = now + 5 * min, label = "Maths"))
        assertEquals("Pause", text(running).button); assertEquals("Maths", text(running).timerLine)
        val paused = WidgetSnapshot(timer = WidgetTimer(phase = "focus", running = true, paused = true, leftMs = 90_000))
        assertEquals("Resume", text(paused).button); assertEquals("Paused · 01:30 left", text(paused).timerLine)
        assertEquals("Short break", text(WidgetSnapshot(timer = WidgetTimer(next = "short", nextMinutes = 5))).timerHead)
    }

    @Test fun theButtonCarriesTheIntentItWasDrawnWith() {
        assertEquals("start", timerCommand(WidgetTimer()))
        assertEquals("pause", timerCommand(WidgetTimer(running = true)))
        assertEquals("resume", timerCommand(WidgetTimer(running = true, paused = true)))
    }
}
