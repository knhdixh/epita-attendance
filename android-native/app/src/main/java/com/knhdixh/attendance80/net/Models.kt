package com.knhdixh.attendance80.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class AuthConfigResponse(
    val google: Boolean = false,
    val dev: Boolean = false,
    val passkeys: Boolean = false,
    val signup: Boolean = false,
)

@Serializable
data class TokenExchangeRequest(
    val code: String,
    val verifier: String,
)

@Serializable
data class TokenResponse(
    val token: String,
)

@Serializable
data class PasskeyRegisterOptionsRequest(
    val name: String? = null,
)

@Serializable
data class PasskeyRegisterVerifyRequest(
    val response: JsonElement,
    val deviceName: String? = null,
)

@Serializable
data class PasskeyRegisterVerifyResponse(
    val ok: Boolean,
    val created: Boolean = false,
    val token: String? = null,
)

@Serializable
data class PasskeyLoginVerifyRequest(
    val response: JsonElement,
)

@Serializable
data class PasskeyLoginVerifyResponse(
    val ok: Boolean,
    val token: String? = null,
)

@Serializable
data class UserProfile(
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
)

@Serializable
data class MeResponse(
    val user: UserProfile? = null,
)

@Serializable
data class UserDto(
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val threshold: Double = 0.8,
    val unit: String = "sessions",
)

@Serializable
data class PasskeyDto(
    val id: String,
    val name: String? = null,
    val backed_up: Int = 0,
    val created_at: Long = 0,
    val last_used_at: Long? = null,
)

@Serializable
data class AccountDto(
    val google: Boolean = false,
    val passkeys: List<PasskeyDto> = emptyList(),
)

@Serializable
data class CourseDto(
    val id: Long,
    val name: String,
    @Serializable(with = IntBooleanSerializer::class) val tracked: Boolean = true,
    val tag: String = "normal",
    @Serializable(with = IntBooleanSerializer::class) val attendance: Boolean = true,
    val recording_url: String? = null,
)

@Serializable
data class EventDto(
    val id: Long,
    val course_id: Long,
    val summary: String,
    val location: String = "",
    val description: String = "",
    val start_at: Long,
    val end_at: Long,
    val status: String? = null,
    val tag: String? = null,
    val note: String = "",
    val cals: List<Long> = emptyList(),
)

@Serializable
data class HomeworkDto(
    val id: Long,
    val course_id: Long? = null,
    val title: String,
    val notes: String = "",
    val due_date: String? = null,
    val due_time: String? = null,
    val tz: String? = null,
    val due_at: Long? = null,
    val remind_minutes: Int? = null,
    @Serializable(with = IntBooleanSerializer::class) val done: Boolean = false,
    val gcal_event_id: String? = null,
    val sync_error: String? = null,
    val created_at: Long = 0,
    val updated_at: Long = 0,
    val kind: String? = null,
    val event_id: Long? = null,
    val effort_minutes: Int? = null,
    val planned_at: Long? = null,
    val planned_minutes: Int? = null,
)

@Serializable
data class BreakDto(
    val id: Long,
    val label: String,
    val start_date: String,
    val end_date: String,
    val sessions: Int = 0,
    val created_at: Long = 0,
)

@Serializable
data class GcalIntegrationDto(
    val email: String? = null,
    val calendarId: String? = null,
    val error: String? = null,
)

@Serializable
data class IntegrationsDto(
    val feedUrl: String,
    val gcalAvailable: Boolean = false,
    val gcal: GcalIntegrationDto? = null,
)

@Serializable
data class CalendarDto(
    val id: Long,
    val kind: String,
    val name: String,
    val site: String? = null,
    val from: String? = null,
    val fileName: String? = null,
    val checkedAt: Long? = null,
    val okAt: Long? = null,
    val error: String? = null,
    val sessions: Int = 0,
    val createdAt: Long = 0,
    @Serializable(with = IntBooleanSerializer::class) val attendance: Boolean = true,
)

@Serializable
data class CalendarSyncDto(
    val source_id: Long? = null,
    val at: Long,
    val source: String? = null,
    val ok: Int = 1,
    val http_status: Int? = null,
    val duration_ms: Long? = null,
    val sessions: Int? = null,
    val added: Int? = null,
    val removed: Int? = null,
    val error: String? = null,
)

@Serializable
data class FocusSettingsDto(
    @SerialName("focus") val focus: Int = 25,
    @SerialName("short") val short: Int = 5,
    @SerialName("long") val long: Int = 15,
    @SerialName("longEvery") val longEvery: Int = 4,
    @SerialName("goal") val goal: Int = 8,
    @SerialName("autoBreaks") val autoBreaks: Boolean = true,
    @SerialName("autoFocus") val autoFocus: Boolean = false,
    @SerialName("sound") val sound: Boolean = true,
    @SerialName("notify") val notify: Boolean = true,
    @SerialName("tasks") val tasks: List<String> = emptyList(),
    @SerialName("tags") val tags: List<String> = emptyList(),
)

@Serializable
data class FocusSessionDto(
    val id: Long = 0,
    val client_id: String,
    val homework_id: Long? = null,
    val course_id: Long? = null,
    val label: String? = null,
    val started_at: Long,
    val ended_at: Long,
    val planned_seconds: Int = 0,
    val focus_seconds: Int = 0,
    @Serializable(with = IntBooleanSerializer::class) val completed: Boolean = true,
    val tags: List<String> = emptyList(),
)

@Serializable
data class FocusDataDto(
    val settings: FocusSettingsDto = FocusSettingsDto(),
    val sessions: List<FocusSessionDto> = emptyList(),
    val since: Long = 0,
)

@Serializable
data class HabitScheduleDto(val type: String, val days: List<Int>? = null, val n: Int? = null)

@Serializable
data class HabitDto(
    val id: Long, val name: String, val icon: String? = null, val color: String,
    val schedule: HabitScheduleDto, val target: Int, val unit: String? = null,
    val remind_at: String? = null, val position: Int, val archived: Boolean, val created_at: Long,
)

@Serializable
data class HabitLogDto(val habit_id: Long, val day: String, val count: Int)

@Serializable
data class HabitsDto(val list: List<HabitDto> = emptyList(), val logs: List<HabitLogDto> = emptyList(), val since: String = "")

@Serializable
data class CountdownDto(
    val id: Long, val title: String, val at: Long,
    @Serializable(with = IntBooleanSerializer::class) val all_day: Boolean,
    val repeat: String, val units: String, val after: String, val start_at: Long? = null,
    val color: String, @Serializable(with = IntBooleanSerializer::class) val pinned: Boolean,
    val note: String? = null, val event_id: Long? = null, val homework_id: Long? = null, val created_at: Long,
)

@Serializable
data class NotifySettingsDto(val classes: Boolean = true, val classLead: Int = 10, val habits: Boolean = true, val focus: Boolean = true, val tz: String = "Europe/Paris")

@Serializable
data class NotificationsDto(val key: String? = null, val settings: NotifySettingsDto = NotifySettingsDto(), val devices: Int = 0)

@Serializable
data class AppData(
    val user: UserDto,
    val account: AccountDto = AccountDto(),
    val courses: List<CourseDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
    val homework: List<HomeworkDto> = emptyList(),
    val breaks: List<BreakDto> = emptyList(),
    val integrations: IntegrationsDto,
    val habits: HabitsDto = HabitsDto(),
    val countdowns: List<CountdownDto> = emptyList(),
    val notifications: NotificationsDto = NotificationsDto(),
    val calendars: List<CalendarDto> = emptyList(),
    val calendarSyncs: List<CalendarSyncDto> = emptyList(),
    val focus: FocusDataDto = FocusDataDto(),
)

@Serializable
data class FocusListResponse(
    val sessions: List<FocusSessionDto>,
    val since: Long,
)

@Serializable
data class FocusLogRequest(
    val client_id: String,
    val homework_id: Long? = null,
    val course_id: Long? = null,
    val label: String? = null,
    val started_at: Long,
    val ended_at: Long,
    val planned_seconds: Int,
    val focus_seconds: Int,
    val completed: Boolean,
    val tags: List<String> = emptyList(),
)

@Serializable
data class FocusSettingsResponse(
    val settings: FocusSettingsDto,
)

@Serializable
data class AttendanceUpdateRequest(
    val status: String? = null,
    val tag: String? = null,
    val note: String? = null,
)

@Serializable
data class AttendanceBulkRequest(
    val ids: List<Long>,
    val status: String,
)

@Serializable
data class AttendanceBulkResponse(
    val updated: Int = 0,
)

@Serializable
data class CourseUpdateRequest(
    val tracked: Boolean? = null,
    val tag: String? = null,
    val recording_url: String? = null,
)

@Serializable
data class CourseTrackedUpdate(
    val id: Long,
    val tracked: Boolean,
)

@Serializable
data class CourseTrackedBulkRequest(
    val updates: List<CourseTrackedUpdate>,
)

@Serializable
data class CatchupRequest(
    val event_id: Long,
    val tz: String,
)

@Serializable
data class HomeworkCreateRequest(
    val title: String,
    val course_id: Long? = null,
    val due_date: String? = null,
    val due_time: String? = null,
    val tz: String,
    val remind_minutes: Int? = null,
    val effort_minutes: Int? = null,
    val notes: String = "",
)

@Serializable
data class HomeworkUpdateRequest(
    val title: String? = null,
    val course_id: Long? = null,
    val due_date: String? = null,
    val due_time: String? = null,
    val tz: String? = null,
    val remind_minutes: Int? = null,
    val done: Boolean? = null,
    val effort_minutes: Int? = null,
    val notes: String? = null,
)

@Serializable
data class BreakCreateRequest(
    val from: String,
    val to: String,
    val label: String,
    val tz: String,
)

@Serializable
data class BreakCreateResponse(
    val `break`: BreakDto,
    val sessions: Int = 0,
)

@Serializable
data class BreakDeleteResponse(
    val restored: Int = 0,
)

@Serializable
data class CalendarAddRequest(
    val kind: String,
    val url: String? = null,
    val ics: String? = null,
    val fileName: String? = null,
    val name: String? = null,
    val from: String? = null,
    val attendance: Boolean? = null,
)

@Serializable
data class CalendarImportSummary(
    val id: Long = 0,
    val name: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val total: Int = 0,
)

@Serializable
data class CalendarUpdateRequest(
    val name: String? = null,
    val attendance: Boolean? = null,
    val from: String? = null,
)

@Serializable
data class CalendarUpdateResponse(
    val calendar: CalendarDto,
    val summary: CalendarImportSummary? = null,
)

@Serializable
data class CalendarFileRequest(
    val ics: String,
    val fileName: String? = null,
)

@Serializable
data class CalendarDeleteResponse(
    val removed: Int = 0,
)

@Serializable
data class GcalResyncResponse(
    val pushed: Int = 0,
    val errors: Int = 0,
)

@Serializable
data class WebLinkRequest(
    val next: String,
)

@Serializable
data class WebLinkResponse(
    val url: String,
)

@Serializable
data class PersonalTokenDto(
    val id: Long,
    val name: String,
    val scope: String,
    val created_at: Long,
    val last_used_at: Long? = null,
)

@Serializable
data class TokenListResponse(
    val tokens: List<PersonalTokenDto>,
)

@Serializable
data class TokenCreateRequest(
    val name: String,
    val scope: String,
)

@Serializable
data class PersonalTokenCreatedDto(
    val id: Long,
    val name: String,
    val scope: String,
    val token: String,
    val created_at: Long,
    val last_used_at: Long? = null,
)

@Serializable
data class SettingsUpdateRequest(
    val threshold: Double? = null,
    val unit: String? = null,
)

@Serializable
data class AccountDeleteRequest(
    val confirm: String = "DELETE",
)
