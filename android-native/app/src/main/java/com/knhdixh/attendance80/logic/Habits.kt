package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.HabitScheduleDto
import com.knhdixh.attendance80.net.HabitDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.*

object Habits {
    val colors = listOf("blue", "green", "teal", "purple", "pink", "red", "orange", "yellow", "gray")
    val DOW = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    fun localKey(ms: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    fun keyInZone(ms: Long, tz: String) = localKey(ms, ZoneId.of(tz))
    fun keyMs(key: String) = LocalDate.parse(key).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun addDays(key: String, n: Int) = LocalDate.parse(key).plusDays(n.toLong()).toString()
    fun diffDays(a: String, b: String) = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(b), LocalDate.parse(a)).toInt()
    fun dow(key: String) = LocalDate.parse(key).dayOfWeek.value
    fun weekOf(key: String) = addDays(key, 1 - dow(key))
    fun monthOf(key: String) = key.substring(0, 7)
    fun isKey(key: String) = Regex("\\d{4}-\\d{2}-\\d{2}").matches(key) && runCatching { LocalDate.parse(key).toString() == key }.getOrDefault(false)

    private fun number(v: JsonElement?): Double? = when (v) {
        null -> null
        JsonNull -> 0.0
        is JsonPrimitive -> if (v.content == "true") 1.0 else if (v.content == "false") 0.0 else if (v.content.isBlank()) 0.0 else v.content.toDoubleOrNull()
        else -> null
    }
    private fun int(v: JsonElement?): Int? = number(v)?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
    fun cleanSchedule(s: JsonElement?): HabitScheduleDto? {
        val obj = s as? JsonObject ?: return null
        val n = int(obj["n"])
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "daily" -> HabitScheduleDto("daily")
            "days" -> (obj["days"] as? JsonArray)?.mapNotNull { int(it) }?.filter { it in 1..7 }?.distinct()?.sorted()?.let { if (it.size == 7) HabitScheduleDto("daily") else if (it.isEmpty()) null else HabitScheduleDto("days", days = it) }
            "weekly" -> if (n != null && n in 1..7) if (n == 7) HabitScheduleDto("daily") else HabitScheduleDto("weekly", n = n) else null
            "monthly" -> if (n != null && n in 1..31) HabitScheduleDto("monthly", n = n) else null
            "interval" -> if (n != null && n in 1..365) if (n == 1) HabitScheduleDto("daily") else HabitScheduleDto("interval", n = n) else null
            else -> null
        }
    }
    private fun str(v: JsonElement?, max: Int): String = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.replace(Regex("\\s+"), " ")?.take(max) ?: ""
    private fun truth(v: JsonElement?): Boolean = when (v) { null, JsonNull -> false; is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.content != "false" && number(v) != 0.0; else -> true }
    private fun scheduleJson(s: HabitScheduleDto): JsonObject = buildJsonObject { put("type", s.type); s.days?.let { put("days", JsonArray(it.map(::JsonPrimitive))) }; s.n?.let { put("n", it) } }
    fun cleanHabit(raw: JsonElement?, partial: Boolean = false): JsonObject {
        val obj = raw as? JsonObject ?: return buildJsonObject { put("ok", false); put("error", "Invalid habit.") }
        val out = mutableMapOf<String, JsonElement>()
        fun error(s: String) = buildJsonObject { put("ok", false); put("error", s) }
        if (!partial || "name" in obj) { val name = str(obj["name"], 60); if (name.isEmpty()) return error("Give the habit a name."); out["name"] = JsonPrimitive(name) }
        if ("icon" in obj) { val text = str(obj["icon"], 32); val icon = text.codePoints().limit(8).toArray().let { String(it, 0, it.size) }; out["icon"] = if (icon.isEmpty()) JsonNull else JsonPrimitive(icon) }
        if (!partial || "color" in obj) { val c = (obj["color"] as? JsonPrimitive)?.contentOrNull; out["color"] = JsonPrimitive(if (c in colors) c!! else "blue") }
        if (!partial || "schedule" in obj) { val s = if ("schedule" in obj) cleanSchedule(obj["schedule"]) else HabitScheduleDto("daily"); if (s == null) return error("Invalid schedule."); out["schedule"] = scheduleJson(s) }
        if (!partial || "target" in obj) { val t = if ("target" in obj) int(obj["target"]) else 1; if (t == null || t !in 1..99) return error("The daily target is a number from 1 to 99."); out["target"] = JsonPrimitive(t) }
        if ("unit" in obj) { val u = str(obj["unit"], 20); out["unit"] = if (u.isEmpty()) JsonNull else JsonPrimitive(u) }
        if ("archived" in obj) out["archived"] = JsonPrimitive(if (truth(obj["archived"])) 1 else 0)
        if ("remind_at" in obj) { val v = obj["remind_at"]; if (!truth(v)) out["remind_at"] = JsonNull else { val s = (v as? JsonPrimitive)?.content ?: ""; if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(s)) return error("Invalid reminder time."); out["remind_at"] = v!! } }
        return buildJsonObject { put("ok", true); put("value", JsonObject(out)) }
    }
    fun reminderDay(habit: HabitDto, now: Long, tz: String, window: Long = 300000) = reminderDay(habit.remind_at, now, tz, window)
    fun reminderDay(remindAt: String?, now: Long, tz: String, window: Long = 300000): String? { if (remindAt.isNullOrEmpty()) return null; val d = Instant.ofEpochMilli(now).atZone(ZoneId.of(tz)); val late = d.hour * 60 + d.minute - (remindAt.substring(0, 2).toInt() * 60 + remindAt.substring(3).toInt()); return if (late >= 0 && late * 60000L < window) d.toLocalDate().toString() else null }
    fun scheduleLabel(s: HabitScheduleDto?): String = when (s?.type) {
        "days" -> when (s.days!!.joinToString("")) { "12345" -> "Weekdays"; "67" -> "Weekends"; else -> s.days.joinToString(", ") { DOW[it - 1] } }
        "weekly" -> if (s.n == 1) "Once a week" else "${s.n}× a week"
        "monthly" -> if (s.n == 1) "Once a month" else "${s.n}× a month"
        "interval" -> if (s.n == 2) "Every other day" else "Every ${s.n} days"
        else -> "Every day"
    }
    @Serializable data class Progress(val done: Int? = null, val of: Int? = null, val since: Int? = null)
    @Serializable data class Stats(val today: Int, val todayDone: Boolean, val due: Boolean, val streak: Int, val best: Int, val unit: String, val progress: JsonElement?, val rate: Double?, val total: Int, val checkins: Int, val weekdays: List<Int>, val start: String)
    @Serializable data class MonthTotal(val month: String, val days: Int)
    fun habitStats(habit: HabitDto, counts: Map<String, Int>, today: String, start: String = today) = habitStats(habit.schedule, habit.target, counts, today, start)
    fun habitStats(s: HabitScheduleDto, target: Int, counts: Map<String, Int>, today: String, start: String = today): Stats {
        val goal = if (target == 0) 1 else target
        fun done(d: String) = (counts[d] ?: 0) >= goal
        val doneDays = counts.keys.filter { it <= today && done(it) }.sorted()
        val first = minOf(start, doneDays.firstOrNull() ?: start)
        val todayDone = done(today)
        var streak = 0; var best = 0; var unit = "day"; var due = true; var progress: JsonElement? = null
        if (s.type == "daily" || s.type == "days") {
            fun isDue(d: String) = s.type == "daily" || dow(d) in s.days.orEmpty()
            var d = if (todayDone) today else addDays(today, -1)
            while (d >= first) { if (isDue(d)) { if (!done(d)) break; streak++ }; d = addDays(d, -1) }
            var run = 0; d = first
            while (d <= today) { if (isDue(d)) { if (done(d)) { run++; best = maxOf(best, run) } else if (d != today) run = 0 }; d = addDays(d, 1) }
            due = isDue(today)
        } else if (s.type == "weekly" || s.type == "monthly") {
            unit = if (s.type == "weekly") "week" else "month"
            fun period(d: String) = if (s.type == "weekly") weekOf(d) else monthOf(d)
            fun prev(p: String) = if (s.type == "weekly") addDays(p, -7) else monthOf(addDays("$p-01", -1))
            fun next(p: String) = if (s.type == "weekly") addDays(p, 7) else monthOf(addDays("$p-01", 32))
            val per = doneDays.groupingBy(::period).eachCount(); val quota = s.n!!
            fun met(p: String) = (per[p] ?: 0) >= quota
            val cur = period(today); val begin = period(first); var p = if (met(cur)) cur else prev(cur)
            while (p >= begin && met(p)) { streak++; p = prev(p) }
            var run = 0; p = begin
            while (p <= cur) { if (met(p)) { run++; best = maxOf(best, run) } else if (p != cur) run = 0; p = next(p) }
            val current = per[cur] ?: 0
            progress = buildJsonObject { put("done", current); put("of", quota) }; due = todayDone || current < quota
        } else if (s.type == "interval") {
            unit = "time"; val last = doneDays.lastOrNull(); val n = s.n!!
            if (last != null && diffDays(today, last) <= n) { streak = 1; for (i in doneDays.lastIndex downTo 1) { if (diffDays(doneDays[i], doneDays[i-1]) > n) break; streak++ } }
            var run = 0
            for (i in doneDays.indices) { run = if (i > 0 && diffDays(doneDays[i], doneDays[i-1]) <= n) run + 1 else 1; best = maxOf(best, run) }
            due = todayDone || last == null || diffDays(today, last) >= n
            if (last != null && !todayDone) progress = buildJsonObject { put("since", diffDays(today, last)) }
        }
        val from = maxOf(first, addDays(today, -29)); val span = diffDays(today, from) + if (todayDone) 1 else 0
        var rate: Double? = null
        if (span > 0) {
            if (s.type == "daily" || s.type == "days") { var planned = 0; var hit = 0; var d = from; while (d < today || (d == today && todayDone)) { if (s.type == "daily" || dow(d) in s.days.orEmpty()) { planned++; if (done(d)) hit++ }; d = addDays(d, 1) }; if (planned > 0) rate = hit.toDouble() / planned }
            else { val expected = if (s.type == "interval") span.toDouble() / s.n!! else span * s.n!! / (if (s.type == "weekly") 7.0 else 30.44); rate = minOf(1.0, doneDays.count { it >= from } / maxOf(expected, 1.0)) }
        }
        val weekdays = MutableList(7) { 0 }; doneDays.forEach { weekdays[dow(it)-1]++ }
        return Stats(counts[today] ?: 0, todayDone, due, streak, maxOf(best, streak), unit, progress, rate, doneDays.size, counts.filterKeys { it <= today }.values.sum(), weekdays, first)
    }
    fun monthTotals(habit: HabitDto, counts: Map<String, Int>, today: String, months: Int = 12) = monthTotals(habit.target, counts, today, months)
    fun monthTotals(target: Int, counts: Map<String, Int>, today: String, months: Int = 12): List<MonthTotal> { val per = counts.filterValues { it >= if (target == 0) 1 else target }.keys.groupingBy(::monthOf).eachCount(); var m = monthOf(today); val out = mutableListOf<MonthTotal>(); repeat(months) { out.add(0, MonthTotal(m, per[m] ?: 0)); m = monthOf(addDays("$m-01", -1)) }; return out }
    fun streakLabel(n: Int, unit: String) = "$n $unit${if (n == 1) "" else "s"}"
    @Serializable data class Parsed(val schedule: HabitScheduleDto?, val target: Int, val unit: String?, val icon: String?, val name: String, val chips: List<String>)
    fun parseHabit(input: String?): Parsed {
        var text = " ${input.orEmpty().trim()} "; var scheduled = false; var schedule: HabitScheduleDto? = HabitScheduleDto("daily"); var target = 1; var unit: String? = null; var icon: String? = null
        fun take(pattern: String, action: (MatchResult) -> Unit): Boolean { val m = Regex(pattern, RegexOption.IGNORE_CASE).find(text) ?: return false; text = text.replaceRange(m.range, " "); action(m); return true }
        // Unicode Extended_Pictographic ranges, generated from the web runtime's Unicode property.
        val pictographic = "[\\x{a9}\\x{ae}\\x{203c}\\x{2049}\\x{2122}\\x{2139}\\x{2194}-\\x{2199}\\x{21a9}-\\x{21aa}\\x{231a}-\\x{231b}\\x{2328}\\x{23cf}\\x{23e9}-\\x{23f3}\\x{23f8}-\\x{23fa}\\x{24c2}\\x{25aa}-\\x{25ab}\\x{25b6}\\x{25c0}\\x{25fb}-\\x{25fe}\\x{2600}-\\x{2604}\\x{260e}\\x{2611}\\x{2614}-\\x{2615}\\x{2618}\\x{261d}\\x{2620}\\x{2622}-\\x{2623}\\x{2626}\\x{262a}\\x{262e}-\\x{262f}\\x{2638}-\\x{263a}\\x{2640}\\x{2642}\\x{2648}-\\x{2653}\\x{265f}-\\x{2660}\\x{2663}\\x{2665}-\\x{2666}\\x{2668}\\x{267b}\\x{267e}-\\x{267f}\\x{2692}-\\x{2697}\\x{2699}\\x{269b}-\\x{269c}\\x{26a0}-\\x{26a1}\\x{26a7}\\x{26aa}-\\x{26ab}\\x{26b0}-\\x{26b1}\\x{26bd}-\\x{26be}\\x{26c4}-\\x{26c5}\\x{26c8}\\x{26ce}-\\x{26cf}\\x{26d1}\\x{26d3}-\\x{26d4}\\x{26e9}-\\x{26ea}\\x{26f0}-\\x{26f5}\\x{26f7}-\\x{26fa}\\x{26fd}\\x{2702}\\x{2705}\\x{2708}-\\x{270d}\\x{270f}\\x{2712}\\x{2714}\\x{2716}\\x{271d}\\x{2721}\\x{2728}\\x{2733}-\\x{2734}\\x{2744}\\x{2747}\\x{274c}\\x{274e}\\x{2753}-\\x{2755}\\x{2757}\\x{2763}-\\x{2764}\\x{2795}-\\x{2797}\\x{27a1}\\x{27b0}\\x{27bf}\\x{2934}-\\x{2935}\\x{2b05}-\\x{2b07}\\x{2b1b}-\\x{2b1c}\\x{2b50}\\x{2b55}\\x{3030}\\x{303d}\\x{3297}\\x{3299}\\x{1f004}\\x{1f02c}-\\x{1f02f}\\x{1f094}-\\x{1f09f}\\x{1f0af}-\\x{1f0b0}\\x{1f0c0}\\x{1f0cf}-\\x{1f0d0}\\x{1f0f6}-\\x{1f0ff}\\x{1f170}-\\x{1f171}\\x{1f17e}-\\x{1f17f}\\x{1f18e}\\x{1f191}-\\x{1f19a}\\x{1f1ae}-\\x{1f1e5}\\x{1f201}-\\x{1f20f}\\x{1f21a}\\x{1f22f}\\x{1f232}-\\x{1f23a}\\x{1f23c}-\\x{1f23f}\\x{1f249}-\\x{1f25f}\\x{1f266}-\\x{1f321}\\x{1f324}-\\x{1f393}\\x{1f396}-\\x{1f397}\\x{1f399}-\\x{1f39b}\\x{1f39e}-\\x{1f3f0}\\x{1f3f3}-\\x{1f3f5}\\x{1f3f7}-\\x{1f3fa}\\x{1f400}-\\x{1f4fd}\\x{1f4ff}-\\x{1f53d}\\x{1f549}-\\x{1f54e}\\x{1f550}-\\x{1f567}\\x{1f56f}-\\x{1f570}\\x{1f573}-\\x{1f57a}\\x{1f587}\\x{1f58a}-\\x{1f58d}\\x{1f590}\\x{1f595}-\\x{1f596}\\x{1f5a4}-\\x{1f5a5}\\x{1f5a8}\\x{1f5b1}-\\x{1f5b2}\\x{1f5bc}\\x{1f5c2}-\\x{1f5c4}\\x{1f5d1}-\\x{1f5d3}\\x{1f5dc}-\\x{1f5de}\\x{1f5e1}\\x{1f5e3}\\x{1f5e8}\\x{1f5ef}\\x{1f5f3}\\x{1f5fa}-\\x{1f64f}\\x{1f680}-\\x{1f6c5}\\x{1f6cb}-\\x{1f6d2}\\x{1f6d5}-\\x{1f6e5}\\x{1f6e9}\\x{1f6eb}-\\x{1f6f0}\\x{1f6f3}-\\x{1f6ff}\\x{1f7da}-\\x{1f7ff}\\x{1f80c}-\\x{1f80f}\\x{1f848}-\\x{1f84f}\\x{1f85a}-\\x{1f85f}\\x{1f888}-\\x{1f88f}\\x{1f8ae}-\\x{1f8af}\\x{1f8bc}-\\x{1f8bf}\\x{1f8c2}-\\x{1f8cf}\\x{1f8d9}-\\x{1f8ff}\\x{1f90c}-\\x{1f93a}\\x{1f93c}-\\x{1f945}\\x{1f947}-\\x{1f9ff}\\x{1fa58}-\\x{1fa5f}\\x{1fa6e}-\\x{1faff}\\x{1fc00}-\\x{1fffd}]"
        val emoji = Regex("^\\s*($pictographic(?:\\uFE0F|\\u200D$pictographic|[\\x{1F3FB}-\\x{1F3FF}])*)").find(text)
        if (emoji != null) { icon = emoji.groupValues[1]; text = text.replaceFirst(icon, " ") }
        if (!take("\\s(\\d{1,2})\\s*(?:x|×|times)\\s*(?:a|per|/|each)?\\s*day\\b|\\s(\\d{1,2})\\s*(?:x|×|times)\\s+daily\\b") { target = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt() }) {
            if (!take("\\s(\\d{1,2})\\s+([a-z]{2,20})\\s+(?:a|per|each)\\s+day\\b") { target = it.groupValues[1].toInt(); unit = it.groupValues[2].lowercase() }) take("\\s[x×](\\d{1,2})(?=\\s)") { target = it.groupValues[1].toInt() }
        }
        target = target.coerceIn(1, 99)
        fun set(s: HabitScheduleDto?) { schedule = s; scheduled = true }
        fun quota(type: String, n: Int): HabitScheduleDto? = cleanSchedule(buildJsonObject { put("type", type); put("n", n) })
        fun num(s: String) = s.toIntOrNull() ?: mapOf("once" to 1, "twice" to 2, "thrice" to 3, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)[s.lowercase()]!!
        val dayNames = listOf(listOf("mon", "monday"), listOf("tue", "tues", "tuesday"), listOf("wed", "weds", "wednesday"), listOf("thu", "thur", "thurs", "thursday"), listOf("fri", "friday"), listOf("sat", "saturday"), listOf("sun", "sunday"))
        val dayRe = dayNames.flatten().sortedByDescending { it.length }.joinToString("|")
        take("\\s(?:every|each)\\s+other\\s+day\\b") { set(HabitScheduleDto("interval", n = 2)) } ||
        take("\\severy\\s+(\\d{1,3})\\s+days?\\b") { set(quota("interval", it.groupValues[1].toInt())) } ||
        take("\\s(\\d|once|twice|thrice|one|two|three|four|five|six)\\s*(?:x|×|times)?\\s*(?:a|per|/|each)\\s*week\\b|\\s(\\d)\\s*x\\s*/\\s*w(?:ee)?k\\b|\\s(once|twice)\\s+weekly\\b") { set(quota("weekly", num(it.groupValues.drop(1).first { v -> v.isNotEmpty() }))) } ||
        take("\\s(\\d{1,2}|once|twice|thrice|one|two|three|four|five|six)\\s*(?:x|×|times)?\\s*(?:a|per|/|each)\\s*month\\b|\\smonthly\\b") { set(quota("monthly", it.groupValues[1].takeIf(String::isNotEmpty)?.let(::num) ?: 1)) } ||
        take("\\s(?:on\\s+|every\\s+)?weekdays\\b") { set(HabitScheduleDto("days", days = (1..5).toList())) } ||
        take("\\s(?:on\\s+|every\\s+)?weekends?\\b") { set(HabitScheduleDto("days", days = listOf(6, 7))) } ||
        take("\\sweekly\\b|\\s(?:once\\s+)?(?:a|per|each)\\s+week\\b") { set(HabitScheduleDto("weekly", n = 1)) } ||
        take("\\s(?:on\\s+|every\\s+)?((?:$dayRe)s?(?:\\s*(?:,|and|&|/|\\s)\\s*(?:$dayRe)s?)*)(?=\\s)") { m ->
            val days = m.groupValues[1].split(Regex("[\\s,&/]+|and", RegexOption.IGNORE_CASE)).mapNotNull { word -> val w = word.lowercase(); val index = dayNames.indexOfFirst { w in it || w.removeSuffix("s") in it }; if (index < 0) null else index + 1 }.distinct().sorted()
            set(if (days.size == 7 || days.isEmpty()) HabitScheduleDto("daily") else HabitScheduleDto("days", days = days))
        } || take("\\s(?:every\\s*day|everyday|each\\s+day|daily)\\b") { set(HabitScheduleDto("daily")) }
        val name = text.replace(Regex("\\s+"), " ").replace(Regex("^[\\s,–—-]+|[\\s,–—-]+$"), "").trim().take(60)
        val chips = listOfNotNull(if (scheduled) scheduleLabel(schedule) else null, if (target > 1) "$target${unit?.let { " $it" } ?: "×"} a day" else null)
        return Parsed(schedule, target, unit, icon, name, chips)
    }
}
