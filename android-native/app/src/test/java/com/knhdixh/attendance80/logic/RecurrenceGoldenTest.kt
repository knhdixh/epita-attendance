package com.knhdixh.attendance80.logic

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceGoldenTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun resource(p: String) = json.parseToJsonElement(javaClass.classLoader!!.getResource(p)!!.readText()).jsonObject
    private val fixtures = resource("fixtures/tasklang.json")
    private val expected = resource("golden/recurrence_golden.json")
    private val today = fixtures.getValue("today").jsonPrimitive.content
    private fun ruleJson(r: RepeatRule) = json.encodeToJsonElement(RepeatRule.serializer(), r)
    private fun read(text: String, due: String? = null): JsonObject {
        val r = Recurrence.parseRecurrence(text, today, due)
        return if (r.ok) buildJsonObject { put("ok", true); put("rule", ruleJson(r.rule!!)); put("text", Recurrence.formatRecurrence(r.rule)) }
        else buildJsonObject { put("ok", false); put("error", r.error) }
    }
    private fun strings(a: JsonElement) = a.jsonArray.map { it.jsonPrimitive.content }

    @Test fun steps() {
        val actual = JsonArray(fixtures.getValue("recurrence").jsonObject.getValue("steps").jsonArray.map { e ->
            val (text, due, done) = e.jsonArray.map { it.jsonPrimitive.content }
            val r = Recurrence.parseRecurrence(text, today, due)
            buildJsonObject {
                put("text", text); put("due", due); put("done", done); put("read", read(text, due))
                put("next", r.rule?.let { Recurrence.nextOccurrence(it, due, done) }?.let(::JsonPrimitive) ?: JsonNull)
            }
        })
        assertJsonEquals(expected.getValue("steps"), actual)
        assertTrue(actual.size >= 60)
    }

    @Test fun formatsErrorsAndDays() {
        val rec = fixtures.getValue("recurrence").jsonObject
        assertJsonEquals(expected.getValue("formats"), JsonArray(strings(rec.getValue("formats")).map { read(it) }))
        assertJsonEquals(expected.getValue("errors"), JsonArray(strings(rec.getValue("errors")).map { read(it) }))
        assertJsonEquals(expected.getValue("days"), JsonArray(strings(rec.getValue("days")).map { t ->
            buildJsonObject { put("text", t); put("day", Recurrence.parseDay(t, today)?.let(::JsonPrimitive) ?: JsonNull) }
        }))
        assertJsonEquals(expected.getValue("drift"), JsonArray(rec.getValue("drift").jsonArray.map { val (t, d) = strings(it); read(t, d) }))
        assertJsonEquals(expected.getValue("clean"), JsonArray(listOf("Every  Mon,Wed", "banana", "every week until dec 2026", "").map { t ->
            val c = Recurrence.cleanRecurrence(t, today)
            if (c.ok) buildJsonObject { put("ok", true); put("text", c.text); put("rule", ruleJson(c.rule!!)) } else buildJsonObject { put("ok", false); put("error", c.error) }
        }))
    }

    @Test fun seriesKeepGoingFor400Steps() {
        val actual = JsonArray(strings(fixtures.getValue("recurrence").jsonObject.getValue("series")).map { text ->
            var due = "2026-01-31"
            val r = Recurrence.parseRecurrence(text, today, due).rule!!
            val dates = (0 until 400).map { due = Recurrence.nextOccurrence(r, due, due)!!; JsonPrimitive(due) }
            buildJsonObject { put("text", text); put("dates", JsonArray(dates)) }
        })
        assertJsonEquals(expected.getValue("series"), actual)
    }

    @Test fun aChangedExpectationFails() {
        val first = expected.getValue("steps").jsonArray[0].jsonObject
        val wrong = JsonObject(first + ("next" to JsonPrimitive("2026-11-05")))
        val r = Recurrence.parseRecurrence("every day", today, "2026-11-03").rule!!
        assertEquals("2026-11-04", Recurrence.nextOccurrence(r, "2026-11-03", "2026-11-03"))
        assertTrue(runCatching { assertJsonEquals(wrong, buildJsonObject { put("next", "2026-11-04") }) }.isFailure)
    }
}
