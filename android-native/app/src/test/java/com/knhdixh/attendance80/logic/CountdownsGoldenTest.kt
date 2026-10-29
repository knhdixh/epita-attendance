package com.knhdixh.attendance80.logic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.time.*
class CountdownsGoldenTest {
 private val json=Json { encodeDefaults=true; explicitNulls=true }
 private fun resource(p:String)=json.parseToJsonElement(javaClass.classLoader!!.getResource(p)!!.readText()).jsonObject
 private inline fun <reified T> enc(v:T):JsonElement=json.parseToJsonElement(json.encodeToString(v))
 private fun local(s:String,z:ZoneId)=LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
 private fun make(c:JsonObject,z:ZoneId)=buildJsonObject { c.forEach { (k,v)->put(k,if(k in listOf("at","start_at") && v!=JsonNull) JsonPrimitive(local(v.jsonPrimitive.content,z)) else v) } }
 private fun cd(c:JsonObject)=Countdowns.Countdown(c.getValue("at").jsonPrimitive.long,c.getValue("all_day").jsonPrimitive.int!=0,c.getValue("repeat").jsonPrimitive.content,c.getValue("units").jsonPrimitive.content,c.getValue("after").jsonPrimitive.content,c["start_at"]?.jsonPrimitive?.longOrNull)
 @Test fun matchesWebCountdowns() {
  val f=resource("fixtures/countdowns.json"); val expected=resource("golden/countdowns_golden.json")
  val actual=buildJsonObject {
   put("validation",JsonArray(f.getValue("validation").jsonArray.map { JsonArray(listOf(Countdowns.cleanCountdown(it),Countdowns.cleanCountdown(it,true))) }))
   put("cases",JsonArray(f.getValue("cases").jsonArray.map { e->val x=e.jsonObject; val z=ZoneId.of(x.getValue("zone").jsonPrimitive.content); val c=make(x.getValue("c").jsonObject,z); val now=local(x.getValue("now").jsonPrimitive.content,z); val d=cd(c); buildJsonObject { put("c",c); put("now",now); put("occurrence",enc(Countdowns.occurrence(d,now,z))); put("view",enc(Countdowns.view(d,now,z))); put("workdays",Countdowns.workdaysBetween(now,d.at,z)) } }))
   put("dates",JsonArray(f.getValue("dateCases").jsonArray.map { e->val x=e.jsonObject; val z=ZoneId.of(x.getValue("zone").jsonPrimitive.content); val at=local(x.getValue("at").jsonPrimitive.content,z); buildJsonObject { put("at",at); put("start",Countdowns.dayStart(at,z)); put("added",Countdowns.addMonths(at,x.getValue("n").jsonPrimitive.int,z)) } }))
   put("parses",JsonArray(listOf("Europe/Paris","Europe/Helsinki").map { tz->val z=ZoneId.of(tz); val now=local(f.getValue("parseNow").jsonPrimitive.content,z); JsonArray(f.getValue("sentences").jsonArray.map { enc(Countdowns.parseCountdown(it.jsonPrimitive.content,now,z)) }) }))
   put("sorts",JsonArray(listOf("Europe/Paris","Europe/Helsinki").map { tz->val z=ZoneId.of(tz); val now=local(f.getValue("parseNow").jsonPrimitive.content,z); val list=f.getValue("cases").jsonArray.map { it.jsonObject }.filter { it.getValue("zone").jsonPrimitive.content==tz && it.getValue("c").jsonObject.let { c->c.getValue("repeat").jsonPrimitive.content=="none" && c.getValue("units").jsonPrimitive.content=="days" && c.getValue("all_day").jsonPrimitive.int==1 } }.map { make(it.getValue("c").jsonObject,z) }; JsonArray(Countdowns.sortCountdowns(list,now,z,::cd).map { buildJsonObject { put("c",it.c); put("v",enc(it.v)) } }) }))
  }
  assertJsonEquals(expected,actual)
 }
}
