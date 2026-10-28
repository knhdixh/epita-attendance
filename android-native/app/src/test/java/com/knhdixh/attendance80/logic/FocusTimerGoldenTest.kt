package com.knhdixh.attendance80.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FocusTimerGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalArgumentException("Resource not found: $name")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun assertClose(expected: Double?, actual: Double?, msg: String = "") {
        if (expected == null && actual == null) return
        if (expected == null || actual == null) {
            throw AssertionError("Mismatch: expected=$expected, actual=$actual. $msg")
        }
        assertTrue("Expected $expected but got $actual (diff ${abs(expected - actual)} > 1e-9). $msg", abs(expected - actual) <= 1e-9)
    }

    @Serializable
    private data class CleanSettingsItem(
        val name: String,
        val input: JsonObject? = null,
        val output: FocusSettings,
    )

    @Serializable
    private data class CleanTagsItem(
        val input: List<String>? = null,
        val output: List<String>,
    )

    @Serializable
    private data class CleanTasksItem(
        val input: List<String>? = null,
        val output: List<String>,
    )

    @Serializable
    private data class TimerOpItem(
        val name: String,
        val settings: FocusSettings = DEFAULT_SETTINGS,
        val timer: FocusTimer,
        val now: Long,
        val remainingNow: Long,
        val remaining5m: Long,
        val length: Long,
        val progressNow: Double,
        val isDueNow: Boolean,
        val nextLength: Int,
        val start: FocusTimer,
        val pause: FocusTimer,
        val resume: FocusTimer,
        val adjustPlus5: FocusTimer,
        val adjustMinus5: FocusTimer,
        val setLength30: FocusTimer,
        val finishNormal: FinishResult,
        val finishEarly: FinishResult,
        val stop: FinishResult,
    )

    @Serializable
    private data class FocusTimerGoldenData(
        val cleanSettingsResults: List<CleanSettingsItem>,
        val cleanTagsResults: List<CleanTagsItem>,
        val cleanTasksResults: List<CleanTasksItem>,
        val timerOperations: List<TimerOpItem>,
    )

    @Test
    fun testCleanHelpersGolden() {
        val golden = json.decodeFromString<FocusTimerGoldenData>(loadResource("golden/focus_timer_golden.json"))

        for (item in golden.cleanTagsResults) {
            val act = cleanTags(item.input)
            assertEquals("cleanTags(${item.input})", item.output, act)
        }

        for (item in golden.cleanTasksResults) {
            val act = cleanTasks(item.input)
            assertEquals("cleanTasks(${item.input?.size})", item.output, act)
        }
    }

    @Test
    fun testTimerOperationsGolden() {
        val golden = json.decodeFromString<FocusTimerGoldenData>(loadResource("golden/focus_timer_golden.json"))

        for (op in golden.timerOperations) {
            val settings = op.settings
            val t = op.timer
            val now = op.now
            val tag = op.name

            // Remaining, length, progress, isDue, nextLength
            assertEquals("$tag: remainingNow", op.remainingNow, remaining(t, now))
            assertEquals("$tag: remaining5m", op.remaining5m, remaining(t, now + 5 * 60000L))
            assertEquals("$tag: length", op.length, length(t))
            assertClose(op.progressNow, progress(t, now), "$tag: progressNow")
            assertEquals("$tag: isDueNow", op.isDueNow, isDue(t, now))
            assertEquals("$tag: nextLength", op.nextLength, nextLength(t, settings))

            // Start
            val actStart = start(t, settings, now, "new-run-start")
            assertEquals("$tag: start phase", op.start.phase, actStart.phase)
            assertEquals("$tag: start next", op.start.next, actStart.next)
            assertEquals("$tag: start endsAt", op.start.endsAt, actStart.endsAt)
            assertEquals("$tag: start done", op.start.done, actStart.done)

            // Pause & Resume
            val actPause = pause(t, now)
            assertEquals("$tag: pause pausedAt", op.pause.pausedAt, actPause.pausedAt)

            val actResume = resume(t, now + 5 * 60000L)
            assertEquals("$tag: resume endsAt", op.resume.endsAt, actResume.endsAt)
            assertEquals("$tag: resume pausedMs", op.resume.pausedMs, actResume.pausedMs)

            // Adjust
            val actAdjPlus = adjust(t, settings, 5, now)
            assertEquals("$tag: adjust +5 endsAt", op.adjustPlus5.endsAt, actAdjPlus.endsAt)
            assertEquals("$tag: adjust +5 custom", op.adjustPlus5.custom, actAdjPlus.custom)

            val actAdjMinus = adjust(t, settings, -5, now)
            assertEquals("$tag: adjust -5 endsAt", op.adjustMinus5.endsAt, actAdjMinus.endsAt)
            assertEquals("$tag: adjust -5 custom", op.adjustMinus5.custom, actAdjMinus.custom)

            // SetLength
            val actSetLen = setLength(t, settings, 30)
            assertEquals("$tag: setLength30 custom", op.setLength30.custom, actSetLen.custom)

            // Finish normal
            val actFinNorm = finish(t, settings, now, "finish-run-1", early = false)
            assertEquals("$tag: finish normal next phase", op.finishNormal.next.phase, actFinNorm.next.phase)
            assertEquals("$tag: finish normal next done", op.finishNormal.next.done, actFinNorm.next.done)
            assertEquals("$tag: finish normal next next", op.finishNormal.next.next, actFinNorm.next.next)
            if (op.finishNormal.log != null) {
                assertEquals("$tag: finish normal log planned_seconds", op.finishNormal.log.planned_seconds, actFinNorm.log?.planned_seconds)
                assertEquals("$tag: finish normal log focus_seconds", op.finishNormal.log.focus_seconds, actFinNorm.log?.focus_seconds)
                assertEquals("$tag: finish normal log completed", op.finishNormal.log.completed, actFinNorm.log?.completed)
            } else {
                assertEquals("$tag: finish normal log null", null, actFinNorm.log)
            }

            // Finish early
            val actFinEarly = finish(t, settings, now, "finish-run-2", early = true)
            assertEquals("$tag: finish early next phase", op.finishEarly.next.phase, actFinEarly.next.phase)
            assertEquals("$tag: finish early next done", op.finishEarly.next.done, actFinEarly.next.done)
            if (op.finishEarly.log != null) {
                assertEquals("$tag: finish early log completed", op.finishEarly.log.completed, actFinEarly.log?.completed)
            }

            // Stop
            val actStop = stop(t, now)
            assertEquals("$tag: stop next phase", op.stop.next.phase, actStop.next.phase)
            assertEquals("$tag: stop next next", op.stop.next.next, actStop.next.next)
            if (op.stop.log != null) {
                assertEquals("$tag: stop log focus_seconds", op.stop.log.focus_seconds, actStop.log?.focus_seconds)
                assertEquals("$tag: stop log completed", op.stop.log.completed, actStop.log?.completed)
            } else {
                assertEquals("$tag: stop log null", null, actStop.log)
            }
        }
    }
}
