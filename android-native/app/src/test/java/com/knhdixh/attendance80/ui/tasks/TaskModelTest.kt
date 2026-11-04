package com.knhdixh.attendance80.ui.tasks

import com.knhdixh.attendance80.net.LabelDto
import com.knhdixh.attendance80.net.ProjectDto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskModelTest {
    @Test fun priorityNamesFollowTodoistNotTheApi() {
        assertEquals(listOf("p4", "p3", "p2", "p1"), (1..4).map { TaskModel.priorityLabel(it) })
        assertEquals(4, (1..4).map { TaskModel.priorityColor(it) }.distinct().size)
    }

    @Test fun metaLineShowsDueRecurrenceLabelsSubtasksAndProject() {
        val d = taskData(task(1, due = "2026-11-03", recurrence = "every day", labels = listOf(7)), task(2, parent = 1, done = true), task(3, parent = 1),
            projects = listOf(ProjectDto(5, name = "School admin")), labels = listOf(LabelDto(7, "deep work")))
        val meta = TaskModel.meta(d.homework[0].copy(project_id = 5), d, TODAY, showProject = true)
        assertEquals(listOf("Yesterday", "↻ every day", "@deep work", "1/2", "#School admin"), meta.map { it.text })
        assertEquals("overdue", meta[0].kind)
        assertEquals("#Inbox", TaskModel.meta(task(9), d, TODAY, true).last().text)
        assertEquals(listOf("Today"), TaskModel.meta(task(9, due = TODAY), d, TODAY, false).map { it.text })
        assertEquals("Tomorrow", TaskModel.dayLabel("2026-11-05", TODAY))
        assertEquals("1 Jan 2027", TaskModel.dayLabel("2027-01-01", TODAY))
    }

    @Test fun overdueAndTodayAreSortedAndSkipWhatIsExcluded() {
        val d = taskData(task(1, due = TODAY, priority = 1), task(2, due = TODAY, priority = 4), task(3, due = "2026-11-01"), task(4, due = "2026-11-09"), task(5, due = TODAY, done = true), task(6))
        val (over, today) = TaskModel.overdueAndToday(d, TODAY)
        assertEquals(listOf(3L), over.map { it.id })
        assertEquals(listOf(2L, 1L), today.map { it.id })
        assertEquals(listOf(1L), TaskModel.overdueAndToday(d, TODAY, setOf(2L)).second.map { it.id })
    }

    @Test fun inboxIsTopLevelOpenTasksWithoutAProjectInManualOrder() {
        val d = taskData(task(1, position = 2), task(2, position = 1), task(3, project = 9), task(4, parent = 1), task(5, done = true))
        assertEquals(listOf(2L, 1L), TaskModel.inbox(d).map { it.id })
        assertEquals(listOf(2L, 1L, 4L), TaskModel.flatten(d, TaskModel.inbox(d)).map { it.h.id })
        assertEquals(listOf(0, 0, 1), TaskModel.flatten(d, TaskModel.inbox(d)).map { it.depth })
    }

    @Test fun projectTreeNestsSubprojectsAndHidesArchived() {
        val d = taskData(projects = listOf(ProjectDto(1, name = "A"), ProjectDto(2, parent_id = 1, name = "A1"), ProjectDto(3, name = "B", position = 1), ProjectDto(4, name = "Old", archived = true)))
        assertEquals(listOf("A" to 0, "A1" to 1, "B" to 0), TaskModel.projectTree(d).map { it.first.name to it.second })
        assertEquals(setOf(1L, 2L), TaskModel.projectIds(d, 1))
    }

    @Test fun reorderPlacesTheTaskBesideTheRowItWasDroppedOn() {
        val d = taskData(task(1, position = 0), task(2, position = 1), task(3, position = 2))
        val rows = d.homework
        fun body(id: Long, target: Long, below: Boolean) = TaskModel.planMove(d, rows, id, target, below)!!
        assertEquals(2, body(1, 3, true)["position"]!!.jsonPrimitive.int)      // 2,3,1
        assertEquals(0, body(3, 1, false)["position"]!!.jsonPrimitive.int)     // 3,1,2
        assertEquals(1, body(3, 2, false)["position"]!!.jsonPrimitive.int)     // 1,3,2
        assertEquals(JsonNull, body(1, 3, true)["parent_id"])
        assertNull(TaskModel.planMove(d, rows, 1, 1, true))
    }

    @Test fun dragSidewaysNestsUnderTheRowAboveAndUnnests() {
        val d = taskData(task(1), task(2), task(3, parent = 1))
        val nest = TaskModel.planMove(d, d.homework, 2, null, false, nest = 1)!!
        assertEquals(1L, nest["parent_id"]!!.jsonPrimitive.long)
        assertEquals(1, nest["position"]!!.jsonPrimitive.int)       // after the existing subtask
        assertNull(TaskModel.planMove(d, d.homework, 1, null, false, nest = 1))   // nothing above the first row
        val out = TaskModel.planMove(d, d.homework, 3, null, false, nest = -1)!!
        assertEquals(JsonNull, out["parent_id"])
        assertEquals(1, out["position"]!!.jsonPrimitive.int)        // right after its old parent
        assertNull(TaskModel.planMove(d, d.homework, 2, null, false, nest = -1))
    }

    @Test fun dropTargetReadsTheRowUnderTheDraggedCentreOrASidewaysDrag() {
        val tops = listOf(Triple(1L, 0f, 100f), Triple(2L, 100f, 100f), Triple(3L, 200f, 100f))
        assertEquals(Triple<Long?, Boolean, Int>(3L, true, 0), dropTarget(tops, 1, 0f, 180f, 120f))
        assertEquals(Triple<Long?, Boolean, Int>(1L, false, 0), dropTarget(tops, 3, 0f, -190f, 120f))
        assertEquals(Triple<Long?, Boolean, Int>(null, false, 1), dropTarget(tops, 2, 150f, 10f, 120f))
        assertEquals(Triple<Long?, Boolean, Int>(null, false, -1), dropTarget(tops, 2, -150f, 10f, 120f))
    }

    @Test fun titleMarkdownIsInline() {
        val s = TaskModel.inlineMarkdown("Read **chapter 2** and *skim* `code` [link](http://x)")
        assertEquals("Read chapter 2 and skim code link", s.text)
        assertEquals(4, s.spanStyles.size)
    }
}
