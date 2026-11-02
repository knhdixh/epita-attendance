package com.knhdixh.attendance80.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun readResource(path: String): String =
        javaClass.classLoader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Resource not found: $path")

    @Test
    fun testDecodeEmptyData() {
        val content = readResource("api/data_empty.json")
        val data = json.decodeFromString<AppData>(content)

        assertEquals("newuser@example.com", data.user.email)
        assertEquals("newuser", data.user.name)
        assertEquals(0.8, data.user.threshold, 1e-9)
        assertEquals("sessions", data.user.unit)
        assertTrue(data.account.google)
        assertTrue(data.account.passkeys.isEmpty())

        assertTrue(data.courses.isEmpty())
        assertTrue(data.events.isEmpty())
        assertTrue(data.homework.isEmpty())
        assertTrue(data.breaks.isEmpty())
        assertTrue(data.calendars.isEmpty())
        assertTrue(data.calendarSyncs.isEmpty())

        assertTrue(data.habits.list.isEmpty())
        assertTrue(data.countdowns.isEmpty())
        assertEquals("Europe/Paris", data.notifications.settings.tz)
        assertTrue(data.integrations.feedUrl.startsWith("https://app.test/feed/"))
        assertNull(data.integrations.gcal)

        assertEquals(25, data.focus.settings.focus)
        assertEquals(5, data.focus.settings.short)
        assertEquals(15, data.focus.settings.long)
        assertEquals(4, data.focus.settings.longEvery)
        assertEquals(8, data.focus.settings.goal)
        assertTrue(data.focus.settings.autoBreaks)
        assertTrue(data.focus.sessions.isEmpty())
    }

    @Test
    fun testDecodeFullData() {
        val content = readResource("api/data.json")
        val data = json.decodeFromString<AppData>(content)

        assertEquals("student@epita.fr", data.user.email)
        assertEquals("student", data.user.name)
        assertEquals(0.8, data.user.threshold, 1e-9)
        assertTrue(data.account.google)

        assertEquals(2, data.courses.size)
        val maths = data.courses.first { it.id == 1L }
        assertEquals("Mathématiques", maths.name)
        assertTrue(maths.tracked)
        assertTrue(maths.attendance)

        assertEquals(3, data.events.size)
        val event1 = data.events.first { it.id == 1L }
        assertEquals(1L, event1.course_id)
        assertEquals("Mathématiques", event1.summary)
        assertEquals("Amphi 1", event1.location)
        assertEquals(listOf(1L), event1.cals)

        assertEquals(2, data.homework.size)
        val hwNoRemind = data.homework.first { it.title == "Maths Exercise 4" }
        assertNull(hwNoRemind.remind_minutes)
        assertNull(hwNoRemind.due_time)
        assertNull(hwNoRemind.effort_minutes)
        assertNull(hwNoRemind.gcal_event_id)
        assertNull(hwNoRemind.sync_error)

        val hwWithRemind = data.homework.first { it.title == "Algo Binary Trees" }
        assertEquals(60, hwWithRemind.remind_minutes)
        assertEquals("17:00", hwWithRemind.due_time)
        assertEquals(120, hwWithRemind.effort_minutes)
        assertEquals(1793188000000L, hwWithRemind.planned_at)
        assertEquals(45, hwWithRemind.planned_minutes)

        val habit = data.habits.list.single()
        assertEquals("Read", habit.name)
        assertEquals(listOf(1, 3, 5), habit.schedule.days)
        assertEquals(2, habit.target)
        assertEquals("09:30", habit.remind_at)
        assertEquals(2, data.habits.logs.single().count)
        val countdown = data.countdowns.single()
        assertEquals("Exam", countdown.title)
        assertEquals("monthly", countdown.repeat)
        assertTrue(countdown.pinned)
        assertEquals(2L, countdown.homework_id)
        assertEquals(30, data.notifications.settings.classLead)
        assertEquals("Europe/Helsinki", data.notifications.settings.tz)

        assertEquals(1, data.breaks.size)
        val brk = data.breaks[0]
        assertEquals("All Saints Break", brk.label)
        assertEquals("2026-11-02", brk.start_date)
        assertEquals("2026-11-06", brk.end_date)
        assertEquals(3, brk.sessions)

        assertEquals(1, data.calendars.size)
        val cal = data.calendars[0]
        assertEquals("epita_classes", cal.name)
        assertEquals("file", cal.kind)
        assertEquals("epita_classes.ics", cal.fileName)
        assertEquals(3, cal.sessions)
        assertTrue(cal.attendance)

        assertEquals(1, data.calendarSyncs.size)
        assertEquals(1L, data.calendarSyncs[0].source_id)
        assertEquals(3, data.calendarSyncs[0].sessions)

        assertEquals(25, data.focus.settings.focus)
        assertEquals(5, data.focus.settings.short)
        assertEquals(15, data.focus.settings.long)
        assertEquals(4, data.focus.settings.longEvery)
        assertEquals(8, data.focus.settings.goal)
        assertTrue(data.focus.settings.autoBreaks)

        assertEquals(2, data.focus.sessions.size)
        val sessNoLabel = data.focus.sessions.first { it.client_id == "client-focus-no-label-12345" }
        assertNull(sessNoLabel.label)
        assertNull(sessNoLabel.homework_id)
        assertNull(sessNoLabel.course_id)
        assertEquals(listOf("study"), sessNoLabel.tags)

        val sessWithLabel = data.focus.sessions.first { it.client_id == "client-focus-with-label-12345" }
        assertEquals("Algorithms revision", sessWithLabel.label)
        assertEquals(2L, sessWithLabel.course_id)
        assertEquals(listOf("algo", "exam"), sessWithLabel.tags)
    }
}
