package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CountdownDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.*
import kotlin.math.*

object Countdowns {
    const val MAX_COUNTDOWNS = 100
    val UNITS = listOf("auto", "days", "weeks", "months", "hours", "clock", "workdays")
    val REPEATS = listOf("none", "weekly", "monthly", "yearly")
    val AFTER = listOf("up", "past")
    private const val DAY = 86400000L
    private const val HOUR = 3600000L
    private const val MIN = 60000L
    private fun string(v: JsonElement?) = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun truth(v: JsonElement?): Boolean = when (v) { null, JsonNull -> false; is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.content != "false" && v.content != "0"; else -> true }
    private fun integer(v: JsonElement?): Long? = (v as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull?.takeIf { it.isFinite() && it % 1.0 == 0.0 && abs(it) <= 9007199254740991.0 }?.toLong()
    private fun instant(v: JsonElement?) = integer(v)?.let { abs(it) < 200 * 365.25 * DAY } == true
    fun cleanCountdown(raw: JsonElement?, partial: Boolean = false): JsonObject {
        val obj = when (raw) { is JsonObject -> raw; is JsonArray -> JsonObject(emptyMap()); else -> return buildJsonObject { put("ok", false); put("error", "Invalid countdown.") } }
        val out = linkedMapOf<String, JsonElement>()
        fun error(s: String) = buildJsonObject { put("ok", false); put("error", s) }
        if (!partial || "title" in obj) { val s = string(obj["title"])?.trim()?.replace(Regex("\\s+"), " ")?.take(80).orEmpty(); if (s.isEmpty()) return error("Give the countdown a name."); out["title"] = JsonPrimitive(s) }
        if (!partial || "at" in obj) { if (!instant(obj["at"])) return error("Pick a date."); out["at"] = obj.getValue("at") }
        if (!partial || "all_day" in obj) { val v = obj["all_day"]; out["all_day"] = JsonPrimitive(if (v == JsonPrimitive(false) || v == JsonPrimitive(0)) 0 else 1) }
        for ((key, options, default) in listOf(Triple("repeat", REPEATS, "none"), Triple("units", UNITS, "auto"), Triple("after", AFTER, "up"))) if (!partial || key in obj) { val v = if (key in obj) string(obj[key]) else default; if (v !in options) return error("Invalid $key."); out[key] = JsonPrimitive(v!!) }
        if ("start_at" in obj) { val v = obj.getValue("start_at"); if (v != JsonNull && !instant(v)) return error("Invalid start date."); out["start_at"] = v }
        if (!partial || "color" in obj) { val c = string(obj["color"]); out["color"] = JsonPrimitive(if (c in Habits.colors) c!! else "blue") }
        if ("pinned" in obj) out["pinned"] = JsonPrimitive(if (truth(obj["pinned"])) 1 else 0)
        if ("note" in obj) { val note = string(obj["note"])?.trim()?.take(500); out["note"] = if (note.isNullOrEmpty()) JsonNull else JsonPrimitive(note) }
        for (key in listOf("event_id", "homework_id")) if (key in obj) { val v = obj.getValue(key); if (v != JsonNull && integer(v) == null) return error("Invalid $key."); out[key] = v }
        return buildJsonObject { put("ok", true); put("value", JsonObject(out)) }
    }
    private fun zoned(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)
    private fun millis(d: ZonedDateTime) = d.toInstant().toEpochMilli()
    fun dayStart(ms: Long, zone: ZoneId = ZoneId.systemDefault()) = millis(zoned(ms, zone).toLocalDate().atStartOfDay(zone))
    fun addMonths(ms: Long, n: Int, zone: ZoneId = ZoneId.systemDefault()) = millis(zoned(ms, zone).toLocalDateTime().plusMonths(n.toLong()).atZone(zone))
    private fun days(a: Long, b: Long, z: ZoneId) = floor((dayStart(a,z) - dayStart(b,z)).toDouble() / DAY + .5).toInt()
    private fun addDays(ms: Long, n: Long, z: ZoneId) = millis(zoned(ms,z).toLocalDateTime().plusDays(n).atZone(z))
    @Serializable data class Countdown(val at: Long, val all_day: Boolean = true, val repeat: String = "none", val units: String = "auto", val after: String = "up", val start_at: Long? = null)
    fun fromDto(c: CountdownDto) = Countdown(c.at, c.all_day, c.repeat, c.units, c.after, c.start_at)
    @Serializable data class Occurrence(val at: Long, val prev: Long?)
    private fun step(c: Countdown, k: Int, z: ZoneId) = if (c.repeat == "weekly") addDays(c.at, 7L*k, z) else addMonths(c.at, k * if (c.repeat == "yearly") 12 else 1, z)
    private fun end(c: Countdown, at: Long, z: ZoneId) = if (c.all_day) addDays(at, 1, z) else at + MIN
    fun occurrence(c: Countdown, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Occurrence {
        if (c.repeat == "none" || c.repeat.isEmpty() || now < end(c,c.at,zone)) return Occurrence(c.at,null)
        val per = when(c.repeat) { "weekly" -> 7.0*DAY; "monthly" -> 30.44*DAY; else -> 365.25*DAY }
        var k = max(0, floor((now-c.at)/per).toInt()-1)
        while (end(c,step(c,k,zone),zone) <= now) k++
        return Occurrence(step(c,k,zone), if (k>0) step(c,k-1,zone) else c.at)
    }
    fun occurrence(c: CountdownDto, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = occurrence(fromDto(c),now,zone)
    fun workdaysBetween(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): Int { var d=dayStart(from,zone); var n=0; repeat(4000) { d=addDays(d,1,zone); if (d>to) return n; if (zoned(d,zone).dayOfWeek.value <= 5) n++ }; return n }
    @Serializable data class View(val state: String, val at: Long, val big: String, val unit: String, val parts: String, val live: Boolean, val progress: Double?)
    fun view(c: CountdownDto, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = view(fromDto(c),now,zone)
    fun view(c: Countdown, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): View {
        val (at,prev)=occurrence(c,now,zone); val future=if(c.all_day) days(at,now,zone)>0 else at>now; val today=c.all_day && days(at,now,zone)==0
        val state=if(today) "today" else if(future) "future" else if(c.after=="past") "past" else "up"
        val ms=abs(at-now); val d=abs(days(at,now,zone)); val units=c.units.ifEmpty { "auto" }; var live=false
        fun pad(n: Long)=n.toString().padStart(2,'0')
        fun plural(n: Int,w: String)="$n $w${if(n==1) "" else "s"}"
        fun clock(): String { val s=ms/1000; val dd=s/86400; live=true; return "${if(dd>0) "${dd}d " else ""}${pad(s%86400/3600)}:${pad(s%3600/60)}:${pad(s%60)}" }
        var big: String; var unit: String; var parts: String
        when {
            today -> { big="Today"; unit=""; parts=big }
            units=="clock" && !c.all_day -> { big=clock(); unit=""; parts=big }
            units=="hours" || (units=="auto" && !c.all_day && ms<2*DAY) -> if(units=="auto" && ms<HOUR) { big=clock().removePrefix("00:"); unit=""; parts=big } else { val h=(ms/HOUR).toInt(); val m=ms%HOUR/MIN; big="$h"; unit=if(h==1) "hour" else "hours"; parts=if(units=="hours") plural(h,"hour") else "$h h ${pad(m)} min"; live=units=="auto" }
            units=="weeks" -> { val w=d/7; val rest=d%7; big="$w"; unit=if(w==1) "week" else "weeks"; parts=if(w>0) "$w w${if(rest>0) " $rest d" else ""}" else plural(rest,"day") }
            units=="months" -> { val a=if(future) now else at; val b=if(future) at else now; var m=0; while(addMonths(dayStart(a,zone),m+1,zone)<=dayStart(b,zone)) m++; val rest=days(b,addMonths(dayStart(a,zone),m,zone),zone); big="$m"; unit=if(m==1) "month" else "months"; parts=if(m>0) "$m mo${if(rest!=0) " $rest d" else ""}" else plural(rest,"day") }
            units=="workdays" -> { val n=if(future) workdaysBetween(now,at,zone) else workdaysBetween(at,now,zone); big="$n"; unit=if(n==1) "working day" else "working days"; parts="$big $unit" }
            else -> { big="$d"; unit=if(d==1) "day" else "days"; parts="$big $unit" }
        }
        val from=if(c.repeat!="none" && prev!=null) prev else c.start_at; val progress=if(from!=null && at>from) ((now-from).toDouble()/(at-from)).coerceIn(0.0,1.0) else null
        return View(state,at,big,unit,parts,live,progress)
    }
    data class Sorted<T>(val c: T, val v: View)
    fun sortCountdowns(list: List<CountdownDto>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = sortCountdowns(list, now, zone, ::fromDto)
    fun <T> sortCountdowns(list: List<T>, now: Long, zone: ZoneId, countdown: (T) -> Countdown): List<Sorted<T>> = list.map { Sorted(it,view(countdown(it),now,zone)) }.sortedWith(compareBy<Sorted<T>> { listOf("today","future","up","past").indexOf(it.v.state) }.thenComparator { a,b -> if(a.v.state=="future" || a.v.state=="today") a.v.at.compareTo(b.v.at) else b.v.at.compareTo(a.v.at) })
    @Serializable data class Parsed(val repeat: String, val after: String, val all_day: Int, val at: Long, val title: String, val since: Boolean)
    private const val MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val WD_RE = "(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
    fun parseCountdown(input: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Parsed? {
        var text=" ${input.trim()} "
        fun take(pattern: String, insensitive: Boolean = true): MatchResult? { val re=Regex(pattern,if(insensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()); val m=re.find(text); if(m!=null) text=text.replaceRange(m.range," "); return m }
        val since=take("\\s(?:since|from)\\s(?=\\S)")!=null
        val repeat=when { take("\\s(?:every\\s+year|yearly|annually|each\\s+year)\\b")!=null -> "yearly"; take("\\s(?:every\\s+month|monthly|each\\s+month)\\b")!=null -> "monthly"; take("\\s(?:every\\s+week|weekly|each\\s+week)\\b")!=null -> "weekly"; Regex("\\b(birthday|bday|anniversary)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text) -> "yearly"; else -> "none" }
        var m=take("\\s(?:at\\s+)?(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?(?=\\s)") ?: take("\\s(?:at\\s+)?(\\d{1,2})\\s*(am|pm)(?=\\s)") ?: take("\\s(?:at\\s+)?(\\d{1,2})h(\\d{2})?(?=\\s)") ?: take("\\sat\\s+(\\d{1,2})(?=\\s)")
        var hour: Int?=null; var minute=0
        if(m!=null) { val groups=m.groupValues.drop(1); val ampm=groups.firstOrNull { it.equals("am",true)||it.equals("pm",true) }; val min=groups.drop(1).firstOrNull { Regex("\\d{2}").matches(it) }; hour=groups[0].toInt() % (if(ampm!=null) 12 else 24) + if(ampm.equals("pm",true)) 12 else 0; minute=min?.toInt() ?: 0; if(minute>59) hour=null }
        val base=zoned(now,zone); val today=base.toLocalDate(); var date: LocalDate?=null; var yearGiven=false
        fun dateOf(y:Int, mon:Int, day:Int): LocalDate = LocalDate.of(if(y in 0..99) y+1900 else y,1,1).plusMonths((mon-1).toLong()).plusDays((day-1).toLong())
        m=take("\\s(\\d{4})-(\\d{2})-(\\d{2})(?=\\s)",false)
        if(m!=null) { date=dateOf(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt()); yearGiven=true }
        else {
            m=take("\\s(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\.?(?=\\s)",false)
            if(m!=null) { val g=m.groupValues; val y=if(g[3].isEmpty()) base.year else if(g[3].length==2) 2000+g[3].toInt() else g[3].toInt(); date=dateOf(y,g[2].toInt(),g[1].toInt()); yearGiven=g[3].isNotEmpty(); if(date.monthValue!=g[2].toInt()) date=null }
            else {
                m=take("\\s(?:on\\s+|the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+$MONTH_RE\\.?(?:,?\\s+(\\d{4}))?(?=\\s)") ?: take("\\s(?:on\\s+)?$MONTH_RE\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?(?=\\s)")
                if(m!=null) { val g=m.groupValues; val first=g[1][0].isDigit(); val dd=(if(first) g[1] else g[2]).toInt(); val mon=(if(first) g[2] else g[1]).take(3).lowercase(); val mi=listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec").indexOf(mon)+1; date=dateOf(g[3].toIntOrNull() ?: base.year,mi,dd); yearGiven=g[3].isNotEmpty(); if(date.monthValue!=mi) date=null }
                else {
                    m=take("\\sin\\s+(\\d{1,3}|a|an|one|two|three)\\s+(day|week|month|year)s?(?=\\s)")
                    if(m!=null) { val n=when(m.groupValues[1].lowercase()) { "a","an","one"->1; "two"->2; "three"->3; else->m.groupValues[1].toInt() }; date=when(m.groupValues[2].lowercase()) { "day"->today.plusDays(n.toLong()); "week"->today.plusDays(7L*n); "month"->today.plusMonths(n.toLong()); else->today.plusYears(n.toLong()) }; yearGiven=true }
                    else {
                        m=take("\\s(today|tonight|tomorrow|tmr|yesterday)(?=\\s)")
                        if(m!=null) { val w=m.groupValues[1].lowercase(); date=today.plusDays(when(w){"tomorrow","tmr"->1; "yesterday"->-1; else->0}); if(w=="tonight" && hour==null) hour=20; yearGiven=true }
                        else { m=take("\\s(?:on\\s+|next\\s+|this\\s+|last\\s+)?$WD_RE(?=\\s)"); if(m!=null) { val wd=listOf("sun","mon","tue","wed","thu","fri","sat").indexOf(m.groupValues[1].take(3).lowercase()); var delta=(wd-today.dayOfWeek.value%7+7)%7; if(delta==0) delta=7; if(since || m.value.contains("last",true)) delta-=7; date=today.plusDays(delta.toLong()); yearGiven=true } }
                    }
                }
            }
        }
        if(date==null && repeat=="monthly") { m=take("\\s(?:on\\s+the\\s+|the\\s+)?(\\d{1,2})(?:st|nd|rd|th)(?=\\s)"); if(m!=null) { date=dateOf(base.year,base.monthValue,m.groupValues[1].toInt()); if(date<today) date=date.plusMonths(1); yearGiven=true } }
        var localDate=date ?: return null
        fun timestamp(d:LocalDate)=millis(d.atTime(hour ?: 0,if (hour == null) 0 else minute).atZone(zone))
        if(!yearGiven) { val at=timestamp(localDate); val end=if(hour==null) addDays(at,1,zone) else at; if(!since && end<=now) localDate=dateOf(localDate.year+1,localDate.monthValue,localDate.dayOfMonth); if(since && at>now) localDate=dateOf(localDate.year-1,localDate.monthValue,localDate.dayOfMonth) }
        val title=text.replace(Regex("\\s+(?:on|at|in|until|till|to|by|the)\\s*$",RegexOption.IGNORE_CASE)," ").replace(Regex("^\\s*(?:until|till|to)\\s+",RegexOption.IGNORE_CASE)," ").replace(Regex("\\s+")," ").replace(Regex("^[\\s,–—:-]+|[\\s,–—:-]+$"),"").trim().take(80)
        return Parsed(repeat,"up",if(hour==null) 1 else 0,timestamp(localDate),title,since)
    }
}
