package com.knhdixh.attendance80.logic

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddGoldenTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun resource(p: String) = json.parseToJsonElement(javaClass.classLoader!!.getResource(p)!!.readText()).jsonObject
    private val f = resource("fixtures/tasklang.json")
    private val q = f.getValue("quickadd").jsonObject
    private val expected = resource("golden/quickadd_golden.json")
    private val today = f.getValue("today").jsonPrimitive.content
    private fun strings(a: JsonElement) = a.jsonArray.map { it.jsonPrimitive.content }
    private fun enc(r: QuickAdd) = json.encodeToJsonElement(QuickAdd.serializer(), r)
    private fun str(s: String?) = s?.let(::JsonPrimitive) ?: JsonNull

    @Test fun inputs() {
        val actual = JsonArray(strings(q.getValue("inputs")).map { t -> buildJsonObject { put("text", t); put("out", enc(QuickAddParser.parseQuickAdd(t, today))) } })
        assertJsonEquals(expected.getValue("inputs"), actual)
        assertTrue(actual.size > 90)
    }

    @Test fun todaysAndPhrasesAndMinutes() {
        val actual = JsonArray(strings(q.getValue("todays")).flatMap { d -> strings(q.getValue("todayInputs")).map { t ->
            buildJsonObject { put("today", d); put("text", t); put("out", enc(QuickAddParser.parseQuickAdd(t, d))) } } })
        assertJsonEquals(expected.getValue("today"), actual)
        assertJsonEquals(expected.getValue("phrases"), JsonArray(strings(q.getValue("phraseToday")).flatMap { d -> strings(q.getValue("phrases")).map { t ->
            buildJsonObject { put("today", d); put("text", t); put("day", str(QuickAddParser.dayFromPhrase(t, d))) } } }))
        assertJsonEquals(expected.getValue("minutes"), JsonArray(strings(q.getValue("minutes")).map { t ->
            buildJsonObject { put("text", t); put("minutes", QuickAddParser.minutesFrom(t)?.let(::JsonPrimitive) ?: JsonNull) } }))
    }

    @Test fun namesThatExist() {
        val n = q.getValue("names").jsonObject
        val names = QuickAddNames(strings(n.getValue("projects")), strings(n.getValue("sections")), strings(n.getValue("labels")))
        val actual = JsonArray(strings(q.getValue("named")).map { t -> buildJsonObject { put("text", t); put("out", enc(QuickAddParser.parseQuickAdd(t, today, names))) } })
        assertJsonEquals(expected.getValue("named"), actual)
        val r = QuickAddParser.parseQuickAdd("Read chapter 3 tom at 9am #School /Week 1", today, names)
        assertEquals("Read chapter 3", r.title); assertEquals("School", r.project); assertEquals("Week 1", r.section)
    }

    @Test fun rangesNeverOverlapAndLeaveTheTitle() {
        for (t in strings(q.getValue("inputs"))) {
            val r = QuickAddParser.parseQuickAdd(t, today)
            var end = 0
            for (x in r.ranges) { assertEquals(t, t.substring(x.start, x.end), x.text); assertTrue("overlap in $t", x.start >= end); end = x.end }
        }
    }
}
