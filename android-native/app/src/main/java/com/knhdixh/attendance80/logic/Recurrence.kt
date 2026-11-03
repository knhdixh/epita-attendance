package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.logic.Habits.addDays
import com.knhdixh.attendance80.logic.Habits.diffDays
import com.knhdixh.attendance80.logic.Habits.dow
import com.knhdixh.attendance80.logic.Habits.isKey
import com.knhdixh.attendance80.logic.Habits.weekOf
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Port of `public/recurrence.js`: Todoist-style repeats ("every other week", "every 3rd friday", "every! 3 days") on
 * 'YYYY-MM-DD' day keys. Checked against the JS by `RecurrenceGoldenTest`; if they differ, this is wrong.
 */
@Serializable
data class RepeatNth(val n: Int, val dow: Int)

@Serializable
data class RepeatRule(
    var unit: String? = null, var interval: Int = 1, var days: List<Int>? = null, var nth: RepeatNth? = null,
    var monthDay: Int? = null, var month: Int? = null, var fromCompletion: Boolean = false, var start: String? = null, var until: String? = null,
)

data class RepeatRead(val ok: Boolean, val rule: RepeatRule? = null, val error: String? = null)

internal const val MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
internal const val WD_RE = "(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
internal val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
internal fun re(p: String) = jsRegex(p, setOf(RegexOption.IGNORE_CASE))
/** What JavaScript's `\s` matches (no-break space, U+2000 to U+200A, line separators, BOM, ...); Java's `\s` is ASCII only. */
private const val JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
/** A regex whose `\s` and `\S` mean what they mean in the JavaScript the logic is ported from. `\d` and `\w` stay ASCII, as in JS. */
internal fun jsRegex(p: String, options: Set<RegexOption> = emptySet()): Regex {
    val out = StringBuilder()
    var i = 0
    while (i < p.length) {
        val c = p[i]
        if (c == '\\' && i + 1 < p.length) {
            when (p[i + 1]) { 's' -> out.append("[$JS_SPACE]"); 'S' -> out.append("[^$JS_SPACE]"); else -> out.append(c).append(p[i + 1]) }
            i += 2
        } else { out.append(c); i++ }
    }
    return Regex(out.toString(), options)
}
internal fun jsRegex(p: String, option: RegexOption) = jsRegex(p, setOf(option))
internal const val WS = "\\s"

object Recurrence {
    private val DAY_ABBR = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private const val MAX_INTERVAL = 999
    private fun pad(n: Int) = n.toString().padStart(2, '0')
    private fun key(y: Int, m: Int, d: Int) = "$y-${pad(m)}-${pad(d)}"
    private fun daysIn(y: Int, m: Int) = java.time.YearMonth.of(y, m).lengthOfMonth()
    private fun parts(k: String) = Triple(k.substring(0, 4).toInt(), k.substring(5, 7).toInt(), k.substring(8, 10).toInt())
    private fun monthIdx(k: String) = parts(k).let { it.first * 12 + it.second - 1 }
    private fun mod(a: Int, n: Int) = ((a % n) + n) % n
    private fun dayIndex(w: String) = DAY_ABBR.indexOf(w.take(3)) + 1
    private fun ordinal(n: Int): String = "$n${if (n % 100 in 11..13) "th" else listOf("th", "st", "nd", "rd").getOrElse(n % 10) { "th" }}"
    private val ORD_WORDS = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "last" to -1)

    private fun clamped(y: Int, m: Int, d: Int) = key(y, m, minOf(d, daysIn(y, m)))

    // ------------------------------------------------------------ reading dates

    /** 'today', 'tomorrow', '2026-06-01', '1 jun', 'jun 1', '1 jun 2026', '1/6', '1/6/2026'. A date without a year is its next occurrence. */
    fun parseDay(text: String, today: String = Habits.localKey()): String? {
        val t = text.trim().lowercase().replace(jsRegex("\\.$"), "")
        fun withYear(y: Int, m: Int, d: Int, explicit: Boolean): String? {
            if (m < 1 || m > 12 || d < 1 || d > 31) return null
            if (explicit) { val k = key(y, m, d); return k.takeIf { isKey(it) } }
            for (yy in y..y + 8) { val k = key(yy, m, d); if (isKey(k) && k >= today) return k }
            return null
        }
        val year = today.substring(0, 4).toInt()
        fun mon(w: String) = MONTHS.indexOf(w.take(3)) + 1
        if (t == "today") return today
        if (t == "tomorrow" || t == "tom") return addDays(today, 1)
        jsRegex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(t)?.let { m -> return withYear(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), true) }
        re("^(\\d{1,2})(?:st|nd|rd|th)?$WS+(?:of$WS+)?$MONTH_RE(?:,?$WS+(\\d{4}))?$").find(t)?.let { m ->
            return withYear(m.groupValues[3].ifEmpty { null }?.toInt() ?: year, mon(m.groupValues[2]), m.groupValues[1].toInt(), m.groupValues[3].isNotEmpty())
        }
        re("^$MONTH_RE$WS+(\\d{1,2})(?:st|nd|rd|th)?(?:,?$WS+(\\d{4}))?$").find(t)?.let { m ->
            return withYear(m.groupValues[3].ifEmpty { null }?.toInt() ?: year, mon(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].isNotEmpty())
        }
        jsRegex("^(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{4}))?$").find(t)?.let { m ->
            return withYear(m.groupValues[3].ifEmpty { null }?.toInt() ?: year, m.groupValues[2].toInt(), m.groupValues[1].toInt(), m.groupValues[3].isNotEmpty())
        }
        return null
    }

    private fun monthEdge(y: Int, m: Int, last: Boolean) = if (last) key(y, m, daysIn(y, m)) else key(y, m, 1)
    private fun dateText(k: String): String { val (y, m, d) = parts(k); return "$d ${MONTHS[m - 1]} $y" }

    // ------------------------------------------------------------ reading a repeat

    private val ALIASES = mapOf(
        "daily" to "every day", "weekly" to "every week", "monthly" to "every month", "yearly" to "every year", "annually" to "every year",
        "weekdays" to "every weekday", "workdays" to "every weekday", "weekends" to "every weekend",
    )
    private val DAYS_LIST = re("^$WD_RE(?:$WS*-$WS*$WD_RE|(?:$WS*(?:,|and|&)$WS*|$WS+)$WD_RE)*$")
    private const val HELP = "I couldn't read that repeat. Try \"every day\", \"every 2 weeks\" or \"every mon, wed\"."
    private val CLAUSE = re("$WS+(until|till|ending|through|starting|beginning|from)$WS+")

    private fun daysOf(text: String): List<Int>? {
        val t = text.replace(jsRegex("\\bon$WS+"), "").trim()
        if (!DAYS_LIST.matches(t)) return null
        val days = sortedSetOf<Int>()
        val range = re("^$WD_RE$WS*-$WS*$WD_RE$").find(t)
        if (range != null) {
            var d = dayIndex(range.groupValues[1])
            while (true) { days.add(d); if (d == dayIndex(range.groupValues[2])) break; d = d % 7 + 1 }
        } else for (w in re(WD_RE).findAll(t)) days.add(dayIndex(w.value))
        return days.toList()
    }

    /** Reads a repeat. `due` is the task's due date: "every month" from the 29th to 31st keeps that day. */
    fun parseRecurrence(input: String?, today: String = Habits.localKey(), due: String? = null): RepeatRead {
        val text = (input ?: "").trim().lowercase().replace(jsRegex("[.]+$"), "").replace(jsRegex("$WS+"), " ")
        if (text.isEmpty()) return RepeatRead(false, error = HELP)
        // JavaScript's split with a capturing group: head, keyword, rest, keyword, rest ...
        val pieces = mutableListOf<String>()
        var at = 0
        for (m in CLAUSE.findAll(text)) { pieces.add(text.substring(at, m.range.first)); pieces.add(m.groupValues[1]); at = m.range.last + 1 }
        pieces.add(text.substring(at))
        val headRaw = pieces[0]; val clauses = pieces.drop(1)
        val head = ALIASES[headRaw] ?: headRaw
        val rule = RepeatRule()

        var i = 0
        while (i < clauses.size) {
            val until = jsRegex("^(until|till|ending|through)$").matches(clauses[i])
            val arg = clauses.getOrNull(i + 1) ?: ""
            val my = re("^$MONTH_RE\\.?,?$WS+(\\d{4})$").find(arg)
            val k = if (my != null) monthEdge(my.groupValues[2].toInt(), MONTHS.indexOf(my.groupValues[1].take(3)) + 1, until) else parseDay(arg, today)
            if (k == null) return RepeatRead(false, error = "I couldn't read the date \"$arg\".")
            if (until) rule.until = k else rule.start = k
            i += 2
        }
        if (rule.start != null && rule.until != null && rule.until!! < rule.start!!) return RepeatRead(false, error = "The end date is before the start date.")

        val m0 = re("^(?:every(!?)|each)$WS*(.*)$").find(head) ?: return RepeatRead(false, error = HELP)
        rule.fromCompletion = m0.groupValues[1] == "!"
        val body = m0.groupValues[2].replace(jsRegex("\\bthe$WS+"), "").replace(jsRegex("$WS*,$WS*"), ", ").trim()
        val bad = RepeatRead(false, error = HELP)

        fun interval(w: String): Int? {
            if (w.isEmpty()) return 1
            if (w == "other") return 2
            val n = w.toIntOrNull() ?: return null
            return n.takeIf { it in 1..MAX_INTERVAL }
        }

        val m = jsRegex("^(?:(other|\\d+)$WS+)?(day|week|month|year)s?(?:$WS+(?:on$WS+)?(.+))?$").find(body)
        if (m != null) {
            val n = interval(m.groupValues[1]) ?: return RepeatRead(false, error = "Repeat every 1 to $MAX_INTERVAL.")
            rule.unit = m.groupValues[2]; rule.interval = n
            val rest = m.groups[3]?.value
            if (!rest.isNullOrEmpty()) {
                when (rule.unit) {
                    "week" -> { rule.days = daysOf(rest); if (rule.days == null) return bad }
                    "month" -> if (!monthSpec(rule, rest)) return bad
                    "year" -> {
                        val k = if (jsRegex("\\d{4}").containsMatchIn(rest)) null else parseDay(rest, "2000-01-01")
                        if (k == null) return bad
                        val p = parts(k); rule.month = p.second; rule.monthDay = p.third
                    }
                    else -> return bad
                }
            }
        } else if (jsRegex("^(weekday|workday)s?$").matches(body)) { rule.unit = "week"; rule.days = listOf(1, 2, 3, 4, 5) }
        else if (jsRegex("^weekends?$").matches(body)) { rule.unit = "week"; rule.days = listOf(6, 7) }
        else if (daysOf(body).also { rule.days = it } != null) rule.unit = "week"
        else if (monthSpec(rule, body)) rule.unit = "month"
        else if (re("^(?:\\d{1,2}(?:st|nd|rd|th)?$WS+$MONTH_RE|$MONTH_RE$WS+\\d{1,2}(?:st|nd|rd|th)?)$").matches(body) && parseDay(body, "2000-01-01") != null) {
            rule.unit = "year"; val p = parts(parseDay(body, "2000-01-01")!!); rule.month = p.second; rule.monthDay = p.third
        } else return bad

        if (rule.unit == "week" && rule.interval == 1 && rule.days?.size == 7) rule.days = null
        // A repeat that would drift when a short month clamps its day (31 -> 28 -> 28...) keeps the day it started on.
        val from = rule.start ?: due
        if (from != null && !rule.fromCompletion) {
            val (_, fm, fd) = parts(from)
            if (rule.unit == "month" && rule.monthDay == null && rule.nth == null && fd > 28) rule.monthDay = fd
            if (rule.unit == "year" && rule.month == null && fm == 2 && fd == 29) { rule.month = 2; rule.monthDay = 29 }
        }
        return RepeatRead(true, rule)
    }

    private fun monthSpec(rule: RepeatRule, text: String): Boolean {
        val t = text.replace(jsRegex("$WS+of$WS+(?:every$WS+)?month$"), "").trim()
        if (t == "last day") { rule.monthDay = -1; return true }
        re("^(\\d+(?:st|nd|rd|th)|first|second|third|fourth|last)$WS+$WD_RE$").find(t)?.let { m ->
            val n = ORD_WORDS[m.groupValues[1]] ?: jsRegex("^\\d+").find(m.groupValues[1])!!.value.toInt()
            if (!(n == -1 || n in 1..4)) return false
            rule.nth = RepeatNth(n, dayIndex(m.groupValues[2])); return true
        }
        jsRegex("^(\\d{1,2})(?:st|nd|rd|th)$").find(t)?.let { m -> val d = m.groupValues[1].toInt(); if (d in 1..31) { rule.monthDay = d; return true } }
        return false
    }

    // ------------------------------------------------------------ writing a repeat

    private fun dayList(days: List<Int>) = days.joinToString(", ") { DAY_ABBR[it - 1] }

    /** The canonical text of a rule. */
    fun formatRecurrence(rule: RepeatRule): String {
        val every = if (rule.fromCompletion) "every!" else "every"
        val n = rule.interval
        fun per(unit: String) = if (n == 1) unit else if (n == 2) "other $unit" else "$n ${unit}s"
        var s: String
        when (rule.unit) {
            "day" -> s = "$every ${per("day")}"
            "week" -> {
                val days = rule.days?.joinToString(",")
                s = if (rule.days == null) "$every ${per("week")}"
                else if (n == 1 && days == "1,2,3,4,5") "$every weekday"
                else if (n == 1 && days == "6,7") "$every weekend"
                else if (n == 1) "$every ${dayList(rule.days!!)}"
                else "$every ${per("week")} on ${dayList(rule.days!!)}"
            }
            "month" -> {
                val nth = rule.nth
                val spec = if (nth != null) "${if (nth.n == -1) "last" else ordinal(nth.n)} ${DAY_ABBR[nth.dow - 1]}"
                else if (rule.monthDay == -1) "last day" else if (rule.monthDay != null && rule.monthDay != 0) "the ${ordinal(rule.monthDay!!)}" else null
                s = if (spec == null) "$every ${per("month")}"
                else if (n == 1 && (nth != null || rule.monthDay == -1)) "$every $spec"
                else "$every ${per("month")} on $spec"
            }
            else -> {
                val date = rule.month?.takeIf { it != 0 }?.let { "${rule.monthDay} ${MONTHS[it - 1]}" }
                s = if (date == null) "$every ${per("year")}" else if (n == 1) "$every $date" else "$every ${per("year")} on $date"
            }
        }
        rule.start?.let { s += " starting ${dateText(it)}" }
        rule.until?.let { s += " until ${dateText(it)}" }
        return s
    }

    // ------------------------------------------------------------ stepping

    private fun isPlain(r: RepeatRule) = r.unit == "day" || (r.unit == "week" && r.days == null) || (r.unit == "month" && r.nth == null && r.monthDay == null) ||
        (r.unit == "year" && r.month == null)

    private fun addMonthsKey(k: String, n: Int): String {
        val (y, m, d) = parts(k)
        val idx = y * 12 + m - 1 + n
        return clamped(Math.floorDiv(idx, 12), mod(idx, 12) + 1, d)
    }

    private fun after(rule: RepeatRule, anchor: String, base: String): String? {
        val n = rule.interval
        if (rule.unit == "day") {
            if (base < anchor) return anchor
            return addDays(anchor, (Math.floorDiv(diffDays(base, anchor), n) + 1) * n)
        }
        if (rule.unit == "week") {
            val days = rule.days ?: listOf(dow(anchor))
            val aWeek = weekOf(anchor)
            var d = addDays(base, 1)
            for (i in 0 until 7 * n + 14) {
                if (d >= anchor && dow(d) in days && mod(diffDays(weekOf(d), aWeek) / 7, n) == 0) return d
                d = addDays(d, 1)
            }
            return null
        }
        if (rule.unit == "month") {
            val (by, bm) = parts(base)
            var idx = by * 12 + bm - 1
            for (i in 0..12 * n + 12) {
                val cur = idx++
                if (mod(cur - monthIdx(anchor), n) != 0) continue
                val y = Math.floorDiv(cur, 12); val m = mod(cur, 12) + 1
                val cand: String
                val nth = rule.nth
                if (nth != null) {
                    val last = daysIn(y, m)
                    val day = if (nth.n == -1) last - mod(dow(key(y, m, last)) - nth.dow, 7)
                    else 1 + mod(nth.dow - dow(key(y, m, 1)), 7) + (nth.n - 1) * 7
                    if (day < 1 || day > last) continue
                    cand = key(y, m, day)
                } else if (rule.monthDay == -1) cand = key(y, m, daysIn(y, m))
                else cand = clamped(y, m, rule.monthDay ?: parts(anchor).third)
                if (cand > base && cand >= anchor) return cand
            }
            return null
        }
        val (ay, am, ad) = parts(anchor)
        val by = parts(base).first
        for (y in by..by + n + 1) {
            if (mod(y - ay, n) != 0) continue
            val cand = clamped(y, rule.month ?: am, rule.monthDay ?: ad)
            if (cand > base && cand >= anchor) return cand
        }
        return null
    }

    /** The next due date when a task due on [due] is completed on [done], or null when the series has ended. */
    fun nextOccurrence(rule: RepeatRule, due: String, done: String = due): String? {
        var base = if (rule.fromCompletion || done > due) done else due
        rule.start?.let { if (base < addDays(it, -1)) base = addDays(it, -1) }
        val next: String? = if (rule.fromCompletion && isPlain(rule)) {
            when (rule.unit) {
                "day" -> addDays(base, rule.interval)
                "week" -> addDays(base, 7 * rule.interval)
                else -> addMonthsKey(base, rule.interval * (if (rule.unit == "year") 12 else 1))
            }
        } else after(rule, rule.start ?: due, base)
        if (next == null || (rule.until != null && next > rule.until!!)) return null
        return next
    }

    /** For the app: the canonical text of a repeat, or an error to show. */
    fun cleanRecurrence(input: String?, today: String = Habits.localKey(), due: String? = null): RepeatClean {
        val r = parseRecurrence(input, today, due)
        return if (r.ok) RepeatClean(true, formatRecurrence(r.rule!!), r.rule) else RepeatClean(false, null, null, r.error)
    }
}

data class RepeatClean(val ok: Boolean, val text: String? = null, val rule: RepeatRule? = null, val error: String? = null)
