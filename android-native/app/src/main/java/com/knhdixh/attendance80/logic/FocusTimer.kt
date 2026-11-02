package com.knhdixh.attendance80.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
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

fun cleanSettings(raw: JsonElement?): FocusSettings {
    if (raw == null || raw !is JsonObject) return DEFAULT_SETTINGS
    fun intVal(key: String, min: Int, max: Int, default: Int): Int {
        val elem = raw[key] ?: return default
        if (elem is JsonNull) return default
        val prim = elem as? JsonPrimitive ?: return default
        if (prim.isString) {
            val content = prim.content
            if (content.isEmpty()) return default
            val n = content.toDoubleOrNull() ?: return default
            if (n % 1.0 != 0.0) return default
            val intVal = n.toInt()
            return if (intVal in min..max) intVal else default
        }
        val bool = prim.booleanOrNull
        if (bool != null) {
            val intVal = if (bool) 1 else 0
            return if (intVal in min..max) intVal else default
        }
        val n = prim.intOrNull ?: return default
        return if (n in min..max) n else default
    }
    fun boolVal(key: String, default: Boolean): Boolean {
        val elem = raw[key] ?: return default
        val prim = elem as? JsonPrimitive ?: return default
        return prim.booleanOrNull ?: default
    }
    fun listVal(key: String): List<String>? {
        val elem = raw[key] ?: return null
        val arr = elem as? JsonArray ?: return null
        return arr.mapNotNull { item ->
            if (item is JsonNull) null
            else (item as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }
    val d = DEFAULT_SETTINGS
    return FocusSettings(
        focus = intVal("focus", 1, 180, d.focus),
        short = intVal("short", 1, 60, d.short),
        long = intVal("long", 1, 90, d.long),
        longEvery = intVal("longEvery", 2, 12, d.longEvery),
        goal = intVal("goal", 1, 24, d.goal),
        autoBreaks = boolVal("autoBreaks", d.autoBreaks),
        autoFocus = boolVal("autoFocus", d.autoFocus),
        sound = boolVal("sound", d.sound),
        notify = boolVal("notify", d.notify),
        tasks = cleanTasks(listVal("tasks"), MAX_TASKS),
        tags = cleanTags(listVal("tags"), MAX_TAGS),
    )
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
    if (t.phase == "idle") (t.custom?.takeIf { it > 0 } ?: settings.minutes(t.next)) else ((length(t).toDouble() / MIN_MS.toDouble()).roundToInt())

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
    val minutes = if (t.phase == "idle" && (t.custom ?: 0) > 0) t.custom!! else settings.minutes(phase)
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

// Typed time follows the web's minutes, minutes:seconds, or hours:minutes:seconds grammar.
fun parseClock(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size > 3 || parts.any { !Regex("[0-9]{1,3}").matches(it) }) return null
    val n = parts.map { it.toLong() }
    if (n.drop(1).any { it > 59 }) return null
    return when (n.size) {
        1 -> n[0] * MIN_MS
        2 -> (n[0] * 60 + n[1]) * 1000
        else -> ((n[0] * 60 + n[1]) * 60 + n[2]) * 1000
    }
}

fun setRemaining(t: FocusTimer, settings: FocusSettings, ms: Long, now: Long): FocusTimer {
    if (t.phase == "idle") return setLength(t, settings, (ms.toDouble() / MIN_MS).roundToInt())
    val from = t.pausedAt ?: now
    val ceiling = t.startedAt + t.pausedMs + MAX_PHASE_MIN * MIN_MS
    return t.copy(endsAt = max(from + MIN_MS, minOf(ceiling, from + ms)))
}
