package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class LocalEffectsTest {
    private val data = ApiClient.json.decodeFromString<AppData>(javaClass.classLoader!!.getResourceAsStream("api/data.json")!!.bufferedReader().readText())
    private val paris = ZoneId.of("Europe/Paris")
    private fun task(id: Long, title: String = "t$id", project_id: Long? = null, section_id: Long? = null, parent_id: Long? = null, recurrence: String? = null, position: Int = 0) =
        HomeworkDto(id, title = title, due_date = "2030-03-30", due_time = null, tz = "Europe/Paris", due_at = 1, project_id = project_id, section_id = section_id, parent_id = parent_id, recurrence = recurrence, position = position)
    private fun base(vararg rows: HomeworkDto, projects: List<ProjectDto> = emptyList(), sections: List<SectionDto> = emptyList()) = data.copy(homework = rows.toList(), projects = projects, sections = sections)
    private fun m(kind: String, path: String, body: JsonObject = JsonObject(emptyMap()), temp: Long? = null, at: Long = 1_900_000_000_000L) = QueuedMutation("cid$kind", kind, "POST", path, body, temp = temp, at = at)

    @Test fun completingAParentCompletesItsOpenSubtasks() {
        val d = LocalEffects.apply(base(task(1), task(2, parent_id = 1L), task(3, parent_id = 2L)), m("task.complete", "api/homework/1/complete"), paris)
        assertTrue(d.homework.all { it.done && it.completed_at == 1_900_000_000_000L })
    }

    @Test fun aRepeatingTaskMovesOnInsteadOfBeingDoneAndReopensItsSubtasks() {
        val sub = task(2, parent_id = 1L).copy(done = true)
        val d = LocalEffects.apply(base(task(1, recurrence = "every day").copy(due_date = "2030-03-30"), sub), m("task.complete", "api/homework/1/complete", at = 1_900_000_000_000L), paris)
        val row = d.homework.single { it.id == 1L }
        assertFalse(row.done)
        assertEquals(java.time.LocalDate.parse(com.knhdixh.attendance80.logic.Habits.keyInZone(1_900_000_000_000L, "Europe/Paris")).let { if (it.toString() > "2030-03-30") it.plusDays(1).toString() else "2030-03-31" }, row.due_date)
        assertFalse(d.homework.single { it.id == 2L }.done)
    }

    @Test fun anOccurrenceThatHasMovedOnChangesNothing() {
        val d = base(task(1, recurrence = "every day").copy(due_date = "2030-03-31"))
        assertEquals(d, LocalEffects.apply(d, m("task.complete", "api/homework/1/complete", buildJsonObject { put("occurrence", "2030-03-30") }), paris))
    }

    @Test fun deletingAProjectDeletesItsSubprojectsSectionsAndTasks() {
        val p = { id: Long, parent: Long? -> ProjectDto(id, parent, "p$id") }
        val d = base(task(1, project_id = 11L), task(2, project_id = 12L, section_id = 21L), task(3, project_id = 13L), task(4, parent_id = 2L, project_id = 12L),
            projects = listOf(p(10, null), p(11, 10), p(12, 11), p(13, null)), sections = listOf(SectionDto(21, 12, "s")))
        val out = LocalEffects.apply(d, m("project.delete", "api/projects/10"), paris)
        assertEquals(listOf(3L), out.homework.map { it.id })
        assertEquals(listOf(13L), out.projects.map { it.id })
        assertTrue(out.sections.isEmpty())
    }

    @Test fun moveSetsTheThreeIdsRenumbersSiblingsAndSubtasksFollow() {
        val d = base(task(1, position = 0, project_id = 5L), task(2, position = 1, project_id = 5L), task(3, parent_id = 1L, project_id = 5L), task(4, position = 0, project_id = 6L))
        val out = LocalEffects.apply(d, m("task.move", "api/homework/move", buildJsonObject { put("ids", JsonArray(listOf(JsonPrimitive(1L)))); put("project_id", 6L); put("position", 0) }), paris)
        assertEquals(listOf(6L, 6L, 6L), listOf(1L, 3L, 4L).map { id -> out.homework.single { it.id == id }.project_id })
        assertEquals(0, out.homework.single { it.id == 1L }.position); assertEquals(1, out.homework.single { it.id == 4L }.position)
        assertEquals("the subtask stays under its parent", 1L, out.homework.single { it.id == 3L }.parent_id)
    }

    @Test fun aSubtaskCreatedUnderAParentLivesInTheParentsProjectAndSection() {
        val out = LocalEffects.apply(base(task(1, project_id = 5L, section_id = 9L)), m("task.create", "api/homework", buildJsonObject { put("title", "child"); put("due_date", JsonNull); put("parent_id", 1L) }, temp = -1), paris)
        val child = out.homework.single { it.id == -1L }
        assertEquals(listOf(5L, 9L, 1L), listOf(child.project_id, child.section_id, child.parent_id))
        assertNull(child.due_at)
    }

    @Test fun aDueTimeIsReadInTheTasksZone() {
        assertEquals(java.time.ZonedDateTime.of(2026, 12, 1, 9, 0, 0, 0, paris).toInstant().toEpochMilli(), LocalEffects.dueAt("2026-12-01", "09:00", "Europe/Paris"))
        assertEquals(java.time.ZonedDateTime.of(2026, 12, 1, 23, 59, 59, 0, paris).toInstant().toEpochMilli() + 999, LocalEffects.dueAt("2026-12-01", null, "Europe/Paris"))
        assertNull(LocalEffects.dueAt(null, null, "UTC"))
    }

    @Test fun remapFollowsTheTemporaryIdEverywhere() {
        val d = base(task(-2, project_id = -1L, parent_id = null).copy(labels = listOf(-3L)), task(5, parent_id = -2L), projects = listOf(ProjectDto(-1, null, "P")), sections = listOf(SectionDto(-4, -1, "S")))
            .copy(labels = listOf(LabelDto(-3, "l")))
        val out = LocalEffects.remap(LocalEffects.remap(LocalEffects.remap(d, -1, 11), -2, 12), -3, 13)
        assertEquals(11L, out.projects.single().id); assertEquals(11L, out.sections.single().project_id)
        assertEquals(listOf(12L, 5L), out.homework.map { it.id }); assertEquals(12L, out.homework[1].parent_id); assertEquals(11L, out.homework[0].project_id)
        assertEquals(listOf(13L), out.homework[0].labels); assertEquals(13L, out.labels.single().id)
    }

    @Test fun aQueuedChangeNamesTheTemporaryIdAndIsRewrittenWhenItIsKnown() {
        val q = QueuedMutation("c", "task.create", "POST", "api/homework", buildJsonObject { put("parent_id", -2L); put("label_ids", JsonArray(listOf(JsonPrimitive(-1L), JsonPrimitive(7L)))) })
        assertTrue(q.mentions(-2) && q.mentions(-1) && !q.mentions(-3))
        val r = q.remapped(-2, 50).remapped(-1, 40)
        assertEquals(50L, r.body.getValue("parent_id").jsonPrimitive.long); assertEquals(listOf(40L, 7L), r.body.getValue("label_ids").jsonArray.map { it.jsonPrimitive.long })
        val path = QueuedMutation("d", "task.complete", "POST", "api/homework/-2/complete").remapped(-2, 9)
        assertEquals("api/homework/9/complete", path.path)
    }
}
