package com.knhdixh.attendance80.logic

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

const val MIN_MS = 60000L
const val MAX_PHASE_MIN = 180
const val MIN_LOGGED_MS = MIN_MS
const val STALE_MS = 5 * MIN_MS
const val MAX_TASKS = 30
const val MAX_TAGS = 20
const val TAGS_PER_SESSION = 5
const val TASK_LEN = 80
const val TAG_LEN = 24

val PHASES: Map<String, String> = mapOf(
    "focus" to "Focus",
    "short" to "Short break",
    "long" to "Long break",
)

@Serializable
data class FocusTarget(
    val homework_id: Long? = null,
    val course_id: Long? = null,
    val label: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class FocusSettings(
    val focus: Int = 25,
    val short: Int = 5,
    val long: Int = 15,
    val longEvery: Int = 4,
    val goal: Int = 8,
    val autoBreaks: Boolean = true,
    val autoFocus: Boolean = false,
    val sound: Boolean = true,
    val notify: Boolean = true,
    val tasks: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    fun lengthMs(phase: String): Long = minutes(phase) * MIN_MS
    fun minutes(phase: String): Int = when (phase) {
        "focus" -> focus
        "long" -> long
        else -> short
    }
}

val DEFAULT_SETTINGS = FocusSettings()

fun cleanTag(t: String?): String {
    if (t == null) return ""
    val s = t.trim().replace(Regex("^#+"), "").replace(Regex("\\s+"), " ").lowercase()
    return if (s.length > TAG_LEN) s.substring(0, TAG_LEN) else s
}

fun cleanTask(t: String?): String {
    if (t == null) return ""
    val s = t.trim().replace(Regex("\\s+"), " ")
    return if (s.length > TASK_LEN) s.substring(0, TASK_LEN) else s
}

fun cleanTags(list: List<String>?, max: Int = TAGS_PER_SESSION): List<String> {
    if (list == null) return emptyList()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    for (item in list) {
        val cleaned = cleanTag(item)
        if (cleaned.isNotEmpty() && cleaned.lowercase() !in seen && out.size < max) {
            seen.add(cleaned.lowercase())
            out.add(cleaned)
        }
    }
    return out
}

fun cleanTasks(list: List<String>?, max: Int = MAX_TASKS): List<String> {
    if (list == null) return emptyList()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    for (item in list) {
        val cleaned = cleanTask(item)
        if (cleaned.isNotEmpty() && cleaned.lowercase() !in seen && out.size < max) {
            seen.add(cleaned.lowercase())
            out.add(cleaned)
        }
    }
    return out
}

fun cleanSettings(
    focus: Int? = null,
    short: Int? = null,
    long: Int? = null,
    longEvery: Int? = null,
    goal: Int? = null,
    autoBreaks: Boolean? = null,
    autoFocus: Boolean? = null,
    sound: Boolean? = null,
    notify: Boolean? = null,
    tasks: List<String>? = null,
    tags: List<String>? = null,
): FocusSettings {
    val d = DEFAULT_SETTINGS
    return FocusSettings(
        focus = if (focus != null && focus in 1..180) focus else d.focus,
        short = if (short != null && short in 1..60) short else d.short,
        long = if (long != null && long in 1..90) long else d.long,
        longEvery = if (longEvery != null && longEvery in 2..12) longEvery else d.longEvery,
        goal = if (goal != null && goal in 1..24) goal else d.goal,
        autoBreaks = autoBreaks ?: d.autoBreaks,
        autoFocus = autoFocus ?: d.autoFocus,
        sound = sound ?: d.sound,
        notify = notify ?: d.notify,
        tasks = cleanTasks(tasks, MAX_TASKS),
        tags = cleanTags(tags, MAX_TAGS),
    )
}

@Serializable
data class FocusLogEntry(
    val client_id: String,
    val started_at: Long,
    val ended_at: Long,
    val planned_seconds: Int,
    val focus_seconds: Int,
    val completed: Boolean,
    val homework_id: Long? = null,
    val course_id: Long? = null,
    val label: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class FinishResult(
    val log: FocusLogEntry?,
    val next: FocusTimer,
)

@Serializable
data class FocusTimer(
    val phase: String = "idle",
    val next: String = "focus",
    val custom: Int? = null,
    val run: String? = null,
    val startedAt: Long = 0L,
    val endsAt: Long = 0L,
    val pausedAt: Long? = null,
    val pausedMs: Long = 0L,
    val done: Int = 0,
    val target: FocusTarget? = null,
    val updatedAt: Long = 0L,
) {
    fun running(): Boolean = phase != "idle"
    fun paused(): Boolean = pausedAt != null
}

fun idle(done: Int = 0, next: String = "focus", target: FocusTarget? = null): FocusTimer =
    FocusTimer(phase = "idle", next = next, done = done, target = target)

fun remaining(t: FocusTimer, now: Long): Long =
    if (t.phase == "idle") 0L else max(0L, t.endsAt - (t.pausedAt ?: now))

fun length(t: FocusTimer): Long =
    if (t.phase == "idle") 0L else t.endsAt - t.startedAt - t.pausedMs

fun progress(t: FocusTimer, now: Long): Double {
    if (t.phase == "idle") return 0.0
    val len = length(t)
    return if (len <= 0) 1.0 else minOf(1.0, 1.0 - (remaining(t, now).toDouble() / len.toDouble()))
}

fun isDue(t: FocusTimer, now: Long): Boolean =
    t.phase != "idle" && t.pausedAt == null && now >= t.endsAt

fun nextLength(t: FocusTimer, settings: FocusSettings): Int =
    if (t.phase == "idle") t.custom ?: settings.minutes(t.next) else ((length(t).toDouble() / MIN_MS.toDouble()).roundToInt())

fun setLength(t: FocusTimer, settings: FocusSettings, minutes: Int): FocusTimer {
    if (t.phase != "idle") return t
    val m = max(1, minOf(MAX_PHASE_MIN, minutes))
    return if (m == settings.minutes(t.next)) {
        t.copy(custom = null)
    } else {
        t.copy(custom = m)
    }
}

fun adjust(t: FocusTimer, settings: FocusSettings, deltaMin: Int, now: Long): FocusTimer {
    if (t.phase == "idle") return setLength(t, settings, nextLength(t, settings) + deltaMin)
    val floor = (t.pausedAt ?: now) + MIN_MS
    val ceiling = t.startedAt + t.pausedMs + MAX_PHASE_MIN * MIN_MS
    val newEndsAt = max(floor, minOf(ceiling, t.endsAt + deltaMin * MIN_MS))
    return t.copy(endsAt = newEndsAt)
}

fun pause(t: FocusTimer, now: Long): FocusTimer =
    if (t.phase == "idle" || t.pausedAt != null) t else t.copy(pausedAt = now)

fun resume(t: FocusTimer, now: Long): FocusTimer {
    if (t.phase == "idle" || t.pausedAt == null) return t
    val gap = now - t.pausedAt
    return t.copy(
        endsAt = t.endsAt + gap,
        pausedMs = t.pausedMs + gap,
        pausedAt = null,
    )
}

fun start(t: FocusTimer, settings: FocusSettings, now: Long, run: String): FocusTimer {
    val phase = if (t.phase == "idle") t.next else t.phase
    val minutes = if (t.phase == "idle" && t.custom != null) t.custom else settings.minutes(phase)
    return FocusTimer(
        phase = phase,
        next = phase,
        custom = null,
        run = run,
        startedAt = now,
        endsAt = now + minutes * MIN_MS,
        pausedAt = null,
        pausedMs = 0L,
        done = t.done,
        target = t.target,
        updatedAt = t.updatedAt,
    )
}

fun entry(t: FocusTimer, now: Long, completed: Boolean): FocusLogEntry? {
    if (t.phase != "focus" || t.run == null) return null
    val end = minOf(t.pausedAt ?: now, t.endsAt)
    val focusMs = end - t.startedAt - t.pausedMs
    if (focusMs < MIN_LOGGED_MS) return null
    return FocusLogEntry(
        client_id = t.run,
        started_at = t.startedAt,
        ended_at = minOf(now, t.endsAt),
        planned_seconds = (length(t).toDouble() / 1000.0).roundToInt(),
        focus_seconds = (focusMs.toDouble() / 1000.0).roundToInt(),
        completed = completed,
        homework_id = t.target?.homework_id,
        course_id = t.target?.course_id,
        label = t.target?.label,
        tags = cleanTags(t.target?.tags),
    )
}

fun finish(
    t: FocusTimer,
    settings: FocusSettings,
    now: Long,
    run: String,
    early: Boolean = false,
): FinishResult {
    if (t.phase == "idle") return FinishResult(log = null, next = t)
    val fresh = now - t.endsAt < STALE_MS
    if (t.phase == "focus") {
        val log = entry(t, now, !early)
        val done = t.done + if (early) 0 else 1
        val nextPhase = if (!early && done >= settings.longEvery) "long" else "short"
        val after = idle(done = done, next = nextPhase, target = t.target)
        val nextTimer = if (settings.autoBreaks && fresh) start(after, settings, now, run) else after
        return FinishResult(log = log, next = nextTimer)
    }
    val after = idle(done = if (t.phase == "long") 0 else t.done, next = "focus", target = t.target)
    val nextTimer = if (settings.autoFocus && fresh) start(after, settings, now, run) else after
    return FinishResult(log = null, next = nextTimer)
}

fun stop(t: FocusTimer, now: Long): FinishResult =
    FinishResult(log = entry(t, now, false), next = idle(done = t.done, next = "focus", target = t.target))

fun skipIdle(t: FocusTimer): FocusTimer =
    if (t.next == "focus") t else idle(done = if (t.next == "long") 0 else t.done, next = "focus", target = t.target)
