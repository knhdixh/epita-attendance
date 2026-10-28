package com.knhdixh.attendance80.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FocusTimerGoldenTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

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

        for (item in golden.cleanSettingsResults) {
            val act = cleanSettings(item.input)
            assertJsonEquals(
                expected = json.encodeToJsonElement(item.output),
                actual = json.encodeToJsonElement(act),
                path = "cleanSettings(${item.name})"
            )
        }

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

            // Operations deep comparison (updatedAt is an Android-native UI bookkeeping field not present in JS focus.js)
            val ignored = setOf("updatedAt")
            assertJsonEquals(json.encodeToJsonElement(op.start), json.encodeToJsonElement(start(t, settings, now, "new-run-start")), "$tag.start", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.pause), json.encodeToJsonElement(pause(t, now)), "$tag.pause", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.resume), json.encodeToJsonElement(resume(t, now + 5 * 60000L)), "$tag.resume", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.adjustPlus5), json.encodeToJsonElement(adjust(t, settings, 5, now)), "$tag.adjustPlus5", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.adjustMinus5), json.encodeToJsonElement(adjust(t, settings, -5, now)), "$tag.adjustMinus5", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.setLength30), json.encodeToJsonElement(setLength(t, settings, 30)), "$tag.setLength30", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.finishNormal), json.encodeToJsonElement(finish(t, settings, now, "finish-run-1", early = false)), "$tag.finishNormal", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.finishEarly), json.encodeToJsonElement(finish(t, settings, now, "finish-run-2", early = true)), "$tag.finishEarly", ignored)
            assertJsonEquals(json.encodeToJsonElement(op.stop), json.encodeToJsonElement(stop(t, now)), "$tag.stop", ignored)
        }
    }
}
