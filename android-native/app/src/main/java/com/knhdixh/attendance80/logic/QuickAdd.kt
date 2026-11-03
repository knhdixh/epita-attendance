package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.logic.Habits.addDays
import com.knhdixh.attendance80.logic.Habits.dow
import com.knhdixh.attendance80.logic.Habits.weekOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Where a part of the quick-add text was read from, so the sheet can highlight it. `value` is the part's value (a string, or a number for priority, reminder and duration). */
@Serializable
data class QuickRange(val kind: String, val start: Int, val end: Int, val text: String, val value: JsonElement = JsonNull)

@Serializable
data class QuickAdd(
    val title: String = "", val due_date: String? = null, val due_time: String? = null, val recurrence: String? = null,
    val project: String? = null, val section: String? = null, val labels: List<String> = emptyList(), val priority: Int = 1,
    val deadline: String? = null, val reminder: Int? = null, val duration: Int? = null, val ranges: List<QuickRange> = emptyList(),
)

/** Names that exist, for "#School admin" and "/Week 1": the longest known name that fits wins. */
data class QuickAddNames(val projects: List<String> = emptyList(), val sections: List<String> = emptyList(), val labels: List<String> = emptyList())

/** Port of `public/quickadd.js` (checked against it by `QuickAddGoldenTest`). */
object QuickAddParser {
    private const val END = "(?=\$|[\\s,.;!?])"
    private const val START = "(?<!\\S)"
    private const val MAX_MINUTES = 24 * 60
    private const val MAX_REMINDER = 28 * 1440
    private fun pad(n: Int) = n.toString().padStart(2, '0')
    private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val WORD_NUMS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3)
    private const val DATE_PREFIX = "(?:(?:on|by|due)\\s+)?"

    private class DatePattern(val source: String, val key: (MatchResult, String) -> String?)

    private fun g(m: MatchResult, i: Int): String? = m.groups[i]?.value

    private val DATE_PATTERNS = listOf(
        DatePattern("(\\d{4})-(\\d{2})-(\\d{2})") { m, today -> Recurrence.parseDay(m.value, today) },
        DatePattern("(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+$MONTH_RE\\.?(?:,?\\s+(\\d{4}))?") { m, today -> Recurrence.parseDay("${g(m, 1)} ${g(m, 2)}${g(m, 3)?.let { " $it" } ?: ""}", today) },
        DatePattern("$MONTH_RE\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?") { m, today -> Recurrence.parseDay("${g(m, 1)} ${g(m, 2)}${g(m, 3)?.let { " $it" } ?: ""}", today) },
        DatePattern("(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?") { m, today ->
            val y = g(m, 3)?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            if (g(m, 3)?.length == 3) null else Recurrence.parseDay("${g(m, 1)}/${g(m, 2)}${y?.let { "/$it" } ?: ""}", today)
        },
        DatePattern("in\\s+(\\d{1,3}|a|an|one|two|three)\\s+(day|week|month|year)s?") { m, today ->
            val n = WORD_NUMS[g(m, 1)!!.lowercase()] ?: g(m, 1)!!.toInt()
            when (val unit = g(m, 2)!!.lowercase()) {
                "day" -> addDays(today, n)
                "week" -> addDays(today, 7 * n)
                else -> addMonthsKeep(today, if (unit == "month") n else 12 * n)
            }
        },
        DatePattern("next\\s+week") { _, today -> addDays(weekOf(today), 7) },
        DatePattern("next\\s+month") { _, today -> addMonthsKeep(today.substring(0, 8) + "01", 1) },
        DatePattern("(today|tonight|tomorrow|tmr|tom|yesterday)") { m, today ->
            val w = g(m, 1)!!
            if (w.equals("tom", true) && w != "tom") null   // "Tom" is a name, "tom" is tomorrow
            else addDays(today, if (Regex("^(tomorrow|tmr|tom)$", RegexOption.IGNORE_CASE).matches(w)) 1 else if (w.equals("yesterday", true)) -1 else 0)
        },
        DatePattern("(?:this\\s+)?weekend") { _, today -> addDays(today, (6 - dow(today) + 7) % 7) },
        DatePattern("(?:(?:next|this)\\s+)?$WD_RE") { m, today ->
            val wd = WEEKDAYS.indexOf(g(m, 1)!!.lowercase().take(3)) + 1
            addDays(today, ((wd - dow(today) + 7) % 7).let { if (it == 0) 7 else it })
        },
    )

    private fun addMonthsKeep(k: String, n: Int): String {
        val idx = k.substring(0, 4).toInt() * 12 + k.substring(5, 7).toInt() - 1 + n
        val y = Math.floorDiv(idx, 12); val m = idx % 12 + 1
        return "$y-${pad(m)}-${pad(minOf(k.substring(8, 10).toInt(), java.time.YearMonth.of(y, m).lengthOfMonth()))}"
    }

    /** A whole date phrase as a day key (what goes in braces), or null. */
    fun dayFromPhrase(text: String, today: String = Habits.localKey()): String? {
        val t = text.trim()
        for (p in DATE_PATTERNS) {
            val m = re("^$DATE_PREFIX${p.source}\$").find(t)
            val k = m?.let { p.key(it, today) }
            if (k != null) return k
        }
        return null
    }

    // ------------------------------------------------------------ times and durations

    private fun clock(h: String, min: String?, ampm: String?): String? {
        var hour = h.toInt()
        val minute = if (min.isNullOrEmpty()) 0 else min.toInt()
        if (minute > 59) return null
        if (ampm != null) {
            if (hour < 1 || hour > 12) return null
            hour = hour % 12 + (if (ampm.lowercase() == "pm") 12 else 0)
        }
        return if (hour > 23) null else "${pad(hour)}:${pad(minute)}"
    }

    private class TimePattern(val source: String, val read: (MatchResult) -> String?)

    private val TIME_PATTERNS = listOf(
        TimePattern("(?:at\\s+)?(\\d{1,2}):(\\d{2})\\s*(am|pm)?") { m -> clock(g(m, 1)!!, g(m, 2), g(m, 3)) },
        TimePattern("(?:at\\s+)?(\\d{1,2})\\.(\\d{2})\\s*(am|pm)") { m -> clock(g(m, 1)!!, g(m, 2), g(m, 3)) },
        TimePattern("(?:at\\s+)?(\\d{1,2})\\s*(am|pm)") { m -> clock(g(m, 1)!!, "0", g(m, 2)) },
        TimePattern("(?:at\\s+)?(\\d{1,2})h(\\d{2})?") { m -> clock(g(m, 1)!!, g(m, 2), null) },
        TimePattern("at\\s+(\\d{1,2})") { m -> clock(g(m, 1)!!, "0", null) },
    )

    private val MINUTES_OK = Regex("^\\d+(?:[.,]\\d+)?\\s*(?:h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?|d(?:ays?)?)(?:\\s*\\d+\\s*m(?:in(?:ute)?s?)?)?$")
    private val MINUTES_PART = Regex("(\\d+(?:[.,]\\d+)?)\\s*(h|m|d)")

    /** "45m", "2h", "1h30m", "1.5h", "90 min", "1 hour 30 min" in minutes, or null. */
    fun minutesFrom(text: String): Int? {
        val t = text.trim().lowercase()
        if (!MINUTES_OK.matches(t)) return null
        var total = 0.0
        for (m in MINUTES_PART.findAll(t)) total += m.groupValues[1].replace(',', '.').toDouble() * (when (m.groupValues[2]) { "h" -> 60 else -> if (m.groupValues[2] == "d") 1440 else 1 })
        val rounded = Math.floor(total + 0.5)
        return if (rounded >= 1) rounded.toInt() else null
    }

    private const val UNIT_RE = "(?:h(?:ours?|rs?)?|m(?:in(?:ute)?s?)?)"
    private val DURATION = re("${START}for\\s+(\\d+(?:[.,]\\d+)?\\s*$UNIT_RE(?:\\s*\\d+\\s*m(?:in(?:ute)?s?)?)?)$END")
    private val REMINDER = re("$START!(\\d+(?:[.,]\\d+)?\\s*(?:$UNIT_RE(?:\\s*\\d+\\s*m(?:in(?:ute)?s?)?)?|d(?:ays?)?))$END")

    // ------------------------------------------------------------ the parser

    /**
     * Reads quick-add text. After #, / or @ the longest name in [names] that fits is taken (so "#School admin" and
     * "/Week 1" work); with none, or none that fits, it's one word.
     */
    fun parseQuickAdd(input: String?, today: String = Habits.localKey(), names: QuickAddNames = QuickAddNames()): QuickAdd {
        val text = input ?: ""
        var w = text   // what's left to read; matched text is blanked so indices stay those of `text`
        val ranges = mutableListOf<QuickRange>()
        fun claim(kind: String, start: Int, end: Int, value: JsonElement) {
            ranges.add(QuickRange(kind, start, end, text.substring(start, end), value))
            w = w.substring(0, start) + " ".repeat(end - start) + w.substring(end)
        }
        fun take(kind: String, re: Regex, read: (MatchResult) -> JsonElement?): JsonElement? {
            var m = re.find(w)
            while (m != null) {
                val v = read(m)
                if (v != null) { claim(kind, m.range.first, m.range.last + 1, v); return v }
                m = m.next()
            }
            return null
        }
        fun name(s: String) = s.replace(Regex("^\"|\"$"), "").replace(Regex("[,.;:!?]+$"), "")
        fun takeNamed(kind: String, sigil: String, known: List<String>, rx: Regex): String? {
            val sorted = known.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
            val fallback = { m: MatchResult -> name(g(m, 1)!!).takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) }
            if (sorted.isEmpty()) return (take(kind, rx, fallback) as JsonPrimitive?)?.content
            val sg = Regex("$START${Regex.escape(sigil)}")
            var m = sg.find(w)
            while (m != null) {
                val from = m.range.last + 1
                val hit = sorted.firstOrNull { n ->
                    w.substring(minOf(from, w.length), minOf(w.length, from + n.length)).lowercase() == n.lowercase() &&
                        Regex("^$END").containsMatchIn(w.substring(minOf(w.length, from + n.length)))
                }
                if (hit != null) { claim(kind, m.range.first, from + hit.length, JsonPrimitive(hit)); return hit }
                m = m.next()
            }
            return (take(kind, rx, fallback) as JsonPrimitive?)?.content
        }

        var dueDate: String? = null; var dueTime: String? = null; var recurrence: String? = null
        var project: String? = null; var section: String? = null; val labels = mutableListOf<String>()
        var priority = 1; var deadline: String? = null; var reminder: Int? = null; var duration: Int? = null

        // {deadline}
        deadline = (take("deadline", Regex("\\{([^{}]+)\\}")) { m -> dayFromPhrase(g(m, 1)!!, today)?.let(::JsonPrimitive) } as JsonPrimitive?)?.content

        // every ...: the longest run of words after "every" that reads as a repeat.
        var repeatText: String? = null
        run {
            var m = Regex("(?<!\\S)every!?(?=\\s)", RegexOption.IGNORE_CASE).find(w)
            while (m != null && repeatText == null) {
                val words = Regex("\\S+").findAll(w.substring(m.range.first)).toList()
                for (n in minOf(words.size, 16) downTo 2) {
                    val end = m.range.first + words[n - 1].range.first + words[n - 1].value.length
                    val phrase = w.substring(m.range.first, end).replace(Regex("[,;.]+$"), "")
                    if (Recurrence.parseRecurrence(phrase, today).ok) {
                        repeatText = phrase
                        claim("recurrence", m.range.first, m.range.first + phrase.length, JsonNull)
                        break
                    }
                }
                m = m.next()
            }
        }

        project = takeNamed("project", "#", names.projects, Regex("(?<!\\S)#(\"[^\"]+\"|[^\\s#]+)"))
        section = takeNamed("section", "/", names.sections, Regex("(?<!\\S)/(\"[^\"]+\"|\\p{L}[^\\s/]*)"))
        while (true) {
            val label = takeNamed("label", "@", names.labels, Regex("(?<!\\S)@([^\\s@#]+)")) ?: break
            if (labels.none { it.lowercase() == label.lowercase() }) labels.add(label)
        }
        (take("priority", re("${START}p([1-4])$END")) { m -> JsonPrimitive(g(m, 1)!!.toInt()) } as JsonPrimitive?)?.let { priority = 5 - it.content.toInt() }
        reminder = (take("reminder", REMINDER) { m -> minutesFrom(g(m, 1)!!)?.takeIf { it <= MAX_REMINDER }?.let(::JsonPrimitive) } as JsonPrimitive?)?.content?.toInt()
        duration = (take("duration", DURATION) { m -> minutesFrom(g(m, 1)!!)?.takeIf { it <= MAX_MINUTES }?.let(::JsonPrimitive) } as JsonPrimitive?)?.content?.toInt()

        // Time first (like countdowns), so "9.30pm" isn't read as a date.
        for (p in TIME_PATTERNS) {
            if (dueTime != null) break
            dueTime = (take("time", re("$START${p.source}$END")) { m -> p.read(m)?.let(::JsonPrimitive) } as JsonPrimitive?)?.content
        }
        for (p in DATE_PATTERNS) {
            if (dueDate != null) break
            dueDate = (take("date", re("$START$DATE_PREFIX${p.source}$END")) { m -> p.key(m, today)?.let(::JsonPrimitive) } as JsonPrimitive?)?.content
        }

        var finalRecurrence: String? = null
        val rt = repeatText
        if (rt != null) {
            val r = Recurrence.parseRecurrence(rt, today).rule!!
            val plain = r.days == null && r.nth == null && r.monthDay == null && !(r.unit == "year" && r.month != null && r.month != 0)
            if (dueDate == null) {
                dueDate = if (plain && r.start == null) today else Recurrence.nextOccurrence(r, addDays(today, -1)) ?: today
            }
            finalRecurrence = Recurrence.formatRecurrence(Recurrence.parseRecurrence(rt, today, dueDate).rule!!)
            recurrence = finalRecurrence
            val at = ranges.indexOfFirst { it.kind == "recurrence" }
            ranges[at] = ranges[at].copy(value = JsonPrimitive(finalRecurrence))
        }
        if (dueTime != null && dueDate == null) dueDate = today

        val title = w.replace(re("\\s+(?:on|at|by|due|for)\\s*$"), " ").replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\s,–—:-]+|[\\s,–—:-]+$"), "").trim()
        return QuickAdd(title, dueDate, dueTime, recurrence, project, section, labels, priority, deadline, reminder, duration, ranges.sortedBy { it.start })
    }
}
