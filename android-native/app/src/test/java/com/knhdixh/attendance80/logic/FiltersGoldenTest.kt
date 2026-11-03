package com.knhdixh.attendance80.logic

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersGoldenTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun resource(p: String) = json.parseToJsonElement(javaClass.classLoader!!.getResource(p)!!.readText()).jsonObject
    private val f = resource("fixtures/tasklang.json")
    private val fl = f.getValue("filters").jsonObject
    private val expected = resource("golden/filters_golden.json")
    private val today = f.getValue("today").jsonPrimitive.content
    private fun strings(a: JsonElement) = a.jsonArray.map { it.jsonPrimitive.content }
    private fun long(o: JsonObject, k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
    private fun names(a: JsonElement?) = a?.jsonArray.orEmpty().map { val o = it.jsonObject; FilterName(long(o, "id")!!, o.getValue("name").jsonPrimitive.content, long(o, "parent_id"), o["archived"]?.jsonPrimitive?.booleanOrNull == true) }
    private fun context(day: String): FilterContext {
        val c = fl.getValue("ctx").jsonObject
        return FilterContext(day, c.getValue("tasks").jsonArray.map {
            val o = it.jsonObject
            FilterTask(long(o, "id")!!, o.getValue("title").jsonPrimitive.content, o["notes"]?.jsonPrimitive?.content, long(o, "project_id"), long(o, "section_id"), long(o, "parent_id"),
                o["due_date"]?.takeIf { v -> v !is JsonNull }?.jsonPrimitive?.content, null, o["priority"]?.jsonPrimitive?.int ?: 1,
                o.getValue("labels").jsonArray.map { l -> l.jsonPrimitive.long }, long(o, "course_id"), o["recurrence"]?.takeIf { v -> v !is JsonNull }?.jsonPrimitive?.content, (o["done"]?.jsonPrimitive?.int ?: 0) != 0)
        }, names(c["projects"]), names(c["sections"]), names(c["labels"]), names(c["courses"]))
    }
    private fun run(q: String, day: String = today): JsonElement {
        val r = Filters.runFilter(q, context(day))
        return if (r.ok) buildJsonObject {
            put("ok", true)
            put("lists", JsonArray(r.lists.map { l -> JsonArray(l.map { JsonPrimitive(it.id) }) }))
            put("tasks", JsonArray(r.tasks.map { JsonPrimitive(it.id) }))
        } else buildJsonObject { put("ok", false); put("error", r.error); put("at", r.at) }
    }
    private fun parse(q: String): JsonElement {
        val r = Filters.parseFilter(q, today)
        return if (r.ok) buildJsonObject { put("ok", true); put("lists", JsonArray(r.lists.map { it.toJson() })) }
        else buildJsonObject { put("ok", false); put("error", r.error); put("at", r.at) }
    }

    @Test fun queries() {
        val actual = JsonArray(strings(fl.getValue("queries")).map { q -> buildJsonObject { put("query", q); put("out", run(q)) } })
        assertJsonEquals(expected.getValue("queries"), actual)
        assertTrue(actual.size >= 80)
    }

    @Test fun treesAndErrors() {
        assertJsonEquals(expected.getValue("parsed"), JsonArray(strings(fl.getValue("queries")).map { q -> buildJsonObject { put("query", q); put("out", parse(q)) } }))
        assertJsonEquals(expected.getValue("errors"), JsonArray(strings(fl.getValue("errors")).map { q -> buildJsonObject { put("query", q); put("out", parse(q)) } }))
    }

    @Test fun otherDays() {
        val actual = JsonArray(strings(fl.getValue("todays")).flatMap { d -> strings(fl.getValue("todayQueries")).map { q ->
            buildJsonObject { put("today", d); put("query", q); put("out", run(q, d)) } } })
        assertJsonEquals(expected.getValue("today"), actual)
    }

    @Test fun commaGivesSeparateListsInOrder() {
        val r = Filters.runFilter("today, overdue, p1", context(today))
        assertEquals(listOf(listOf(1L, 6L), listOf(2L), listOf(1L, 7L)), r.lists.map { l -> l.map { it.id } })
        assertEquals(listOf(1L, 6L, 2L, 7L), r.tasks.map { it.id })
    }
}
