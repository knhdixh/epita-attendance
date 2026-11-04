package com.knhdixh.attendance80.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Light and dark, and 200% text, for the Todoist screens. The files are `phone411x891_<theme>_tasks_<screen>_font<n>.png`. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class TasksScreenshotTest {
    @get:Rule val ui = createComposeRule()
    private val project = ProjectDto(5, name = "Thesis", view = "list")
    private val data = taskData(
        task(1, "Write **chapter 2**", project = 5, due = "2026-11-03", priority = 4, labels = listOf(7), recurrence = "every week").copy(comment_count = 2),
        task(2, "Outline", parent = 1, project = 5), task(3, "Draft", parent = 1, project = 5, done = true),
        task(4, "Email supervisor", project = 5, section = 9, due = TODAY, priority = 3), task(5, "Book viva", project = 5, section = 9, due = "2026-11-12", priority = 2).copy(deadline_date = "2026-11-20"),
        task(6, "Buy milk"), task(7, "Pay rent", due = TODAY, priority = 4), task(8, "Old thing", done = true).copy(completed_at = taskNow - 3_600_000),
        projects = listOf(project, ProjectDto(6, name = "Home", favorite = true)), sections = listOf(SectionDto(9, 5, "Writing"), SectionDto(10, 5, "Admin", position = 1)),
        labels = listOf(LabelDto(7, "deep work", favorite = true)), filters = listOf(FilterDto(3, "Urgent", "today & p1", favorite = true)))
    private val env = TaskEnv(data, TODAY, taskZone)

    private fun shot(name: String, dark: Boolean, scale: Float, content: @Composable () -> Unit) {
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) { Attendance80Theme(darkTheme = dark) { content() } } }
        ui.waitForIdle()
        ui.onRoot().captureRoboImage("src/test/screenshots/phone411x891_${if (dark) "dark" else "light"}_tasks_${name}_font${scale.toInt()}.png")
    }

    private val inbox: @Composable () -> Unit = { InboxScreen(env) {} }
    private val projectList: @Composable () -> Unit = { ProjectScreen(project, env, NoTaskActions) {} }
    private val board: @Composable () -> Unit = { ProjectScreen(project.copy(view = "board"), env.copy(data = data.copy(projects = listOf(project.copy(view = "board")))), NoTaskActions) {} }
    private val filters: @Composable () -> Unit = { FiltersScreen(env, NoTaskActions) {} }
    private val search: @Composable () -> Unit = { SearchScreen(env, NoTaskActions, listOf("dentist", "viva"), {}, {}, autoFocus = false) }
    private val completed: @Composable () -> Unit = { CompletedScreen(env, object : TaskActions by NoTaskActions { override suspend fun completions(since: Long) = listOf(CompletionDto(1, 6, taskNow - 7_200_000)) }, taskNow) }
    private val browse: @Composable () -> Unit = { BrowseScreen(data, {}) }

    @Test fun lightInbox() = shot("inbox", false, 1f, inbox)
    @Test fun darkInbox() = shot("inbox", true, 1f, inbox)
    @Test fun largeInbox() = shot("inbox", false, 2f, inbox)
    @Test fun lightProject() = shot("project", false, 1f, projectList)
    @Test fun darkProject() = shot("project", true, 1f, projectList)
    @Test fun largeProject() = shot("project", false, 2f, projectList)
    @Test fun lightBoard() = shot("board", false, 1f, board)
    @Test fun darkBoard() = shot("board", true, 1f, board)
    @Test fun lightFilters() = shot("filters", false, 1f, filters)
    @Test fun darkFilters() = shot("filters", true, 1f, filters)
    @Test fun largeFilters() = shot("filters", false, 2f, filters)
    @Test fun lightSearch() = shot("search", false, 1f, search)
    @Test fun darkSearch() = shot("search", true, 1f, search)
    @Test fun lightCompleted() = shot("completed", false, 1f, completed)
    @Test fun darkCompleted() = shot("completed", true, 1f, completed)
    @Test fun lightBrowse() = shot("browse", false, 1f, browse)
    @Test fun darkBrowse() = shot("browse", true, 1f, browse)
    @Test fun largeBrowse() = shot("browse", false, 2f, browse)
    @Test fun lightQuickAdd() = shot("quickadd", false, 1f) { QuickAddSheet(data, TODAY, taskZone, QuickDefaults(), NoTaskActions, initial = "Buy milk tomorrow p1 #Home @deep work !30m", onDismiss = {}) }
    @Test fun darkDetail() = shot("detail", true, 1f) { TaskDetailSheet(1, env, NoTaskActions, {}, {}, {}, {}, {}) }
    @Test fun lightDetail() = shot("detail", false, 1f) { TaskDetailSheet(1, env, NoTaskActions, {}, {}, {}, {}, {}) }
}
