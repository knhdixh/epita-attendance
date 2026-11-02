package com.knhdixh.attendance80.logic

import kotlinx.serialization.json.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarMathGoldenTest {
    private val json = Json
    private fun resource(p: String) = json.parseToJsonElement(javaClass.classLoader!!.getResource(p)!!.readText()).jsonObject
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.i(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double
    private fun local(s: String, z: ZoneId) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    private fun longs(xs: List<Long>) = JsonArray(xs.map(::JsonPrimitive))
    @Test fun matchesWebCalendar() {
        val f = resource("fixtures/calendar.json"); val expected = resource("golden/calendar_golden.json")
        val actual = buildJsonObject {
            put("dates", JsonArray(f.getValue("dates").jsonArray.map { e ->
                val x = e.jsonObject; val z = ZoneId.of(x.s("zone")); val date = local(x.s("date"), z)
                buildJsonObject {
                    put("date", date); put("start", CalendarMath.startOfDay(date,z)); put("plus", CalendarMath.addDays(date,1,z)); put("minus", CalendarMath.addDays(date,-1,z)); put("monday", CalendarMath.mondayOf(date,z)); put("minutes", longs(listOf(90,150,210,540).map { CalendarMath.atMinutes(date,it,z) })); put("week", CalendarMath.isoWeek(date,z))
                    put("views", JsonArray(listOf("day","3day","week","month").map { v -> val p = CalendarMath.period(v,date,z); buildJsonObject { put("period", buildJsonObject { put("start",p.start); put("end",p.end); put("days",longs(p.days)) }); put("before",CalendarMath.shift(v,date,-1,z)); put("after",CalendarMath.shift(v,date,1,z)) } }))
                }
            }))
            val items = f.getValue("items").jsonArray.map { it.jsonObject.let { x -> CalendarMath.Item(x.i("id"),x.i("start").toLong(),x.i("end").toLong()) } }
            put("layout", buildJsonObject { CalendarMath.layout(items).forEach { (id,p) -> put(id.toString(),buildJsonObject { put("col",p.col); put("cols",p.cols) }) } })
            val courses = f.getValue("courses").jsonArray.map { it.jsonObject.let { x -> CalendarMath.Course(x.i("id"),x.getValue("tracked").jsonPrimitive.boolean) } }
            put("colors", buildJsonObject { CalendarMath.courseColors(courses).forEach { (id,c) -> put(id.toString(),c) } })
            put("plans", JsonArray(f.getValue("plans").jsonArray.map { e ->
                val x=e.jsonObject; val z=ZoneId.of(x.s("zone")); fun at(s:String)=local(x.s("date")+"T"+s+":00",z)
                fun intervals(k:String)=x.getValue(k).jsonArray.map { a -> a.jsonArray.let { at(it[0].jsonPrimitive.content) to at(it[1].jsonPrimitive.content) } }
                val todos=x.getValue("todos").jsonArray.map { it.jsonObject.let { t -> CalendarMath.Todo(t.i("id"),at(t.s("due")),t.d("effort")) } }
                val p=CalendarMath.autoPlan(todos,intervals("busy"),intervals("blocks"),at(x.s("now")),local(x.s("until"),z),z,x.i("dayStart"),x.i("dayEnd"),x.i("buffer"),x.d("cap"),x.d("maxBlock"),x.i("step"))
                buildJsonObject { put("planned",JsonArray(p.planned.map { buildJsonObject { put("id",it.id); put("start",it.start); put("minutes",it.minutes) } })); put("unplaced",JsonArray(p.unplaced.map(::JsonPrimitive))) }
            }))
        }
        assertJsonEquals(expected,actual)
    }
}
