package com.knhdixh.attendance80.ui.settings

import com.knhdixh.attendance80.net.*
import java.time.Instant
import java.time.ZoneId

internal val settingsZone: ZoneId = ZoneId.of("Europe/Paris")
internal val settingsNow: Long = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli()
internal fun settingsBase(): AppData = ApiClient.json.decodeFromString(object {}.javaClass.getResource("/api/data.json")!!.readText())

/** Two calendars (a Zeus link that failed and an uploaded file), a passkey, Google Calendar connected with an error. */
internal fun settingsData(): AppData {
    val d = settingsBase()
    val link = CalendarDto(2, "link", "SE1", site = "zeus.ionis-it.com", from = "2026-09-01", okAt = settingsNow - 3 * 3600000, error = "HTTP 503 from zeus.ionis-it.com", sessions = 40)
    return d.copy(
        calendars = d.calendars + link,
        calendarSyncs = d.calendarSyncs + CalendarSyncDto(2, settingsNow - 3600000, "cron", ok = 0, http_status = 503, duration_ms = 2300, error = "HTTP 503"),
        account = AccountDto(google = true, passkeys = listOf(PasskeyDto("pk_123456789", "Pixel 9", backed_up = 1, created_at = settingsNow - 86400000L * 30, last_used_at = settingsNow - 86400000L))),
        integrations = d.integrations.copy(gcalAvailable = true, gcal = GcalIntegrationDto("student@gmail.com", "cal", error = "Google Calendar access was revoked. Reconnect to keep syncing.")),
    )
}

/** Records every call; tokens come from [tokens]. */
internal class FakeSettingsActions(var tokens: List<PersonalTokenDto> = emptyList()) : SettingsActions {
    val calls = mutableListOf<String>()
    val messages = mutableListOf<String>()
    override suspend fun saveRules(threshold: Double, unit: String) = true.also { calls += "rules $threshold $unit" }
    override suspend fun saveNotify(body: kotlinx.serialization.json.JsonObject) = true.also { calls += "notify $body" }
    override suspend fun addLink(url: String, name: String, from: String?) = true.also { calls += "link $url|$name|$from" }
    override suspend fun editCalendar(id: Long, name: String, attendance: Boolean, from: String?) = true.also { calls += "edit $id $name $attendance $from" }
    override suspend fun syncCalendar(id: Long) = true.also { calls += "sync $id" }
    override suspend fun removeCalendar(id: Long) = true.also { calls += "remove $id" }
    override suspend fun rotateFeed() = true.also { calls += "rotate" }
    override suspend fun gcalResync(quiet: Boolean) = true.also { calls += "resync" }
    override suspend fun gcalDisconnect() = true.also { calls += "disconnect" }
    override suspend fun loadTokens() = tokens
    override suspend fun createToken(name: String, scope: String) = PersonalTokenCreatedDto(77, name, scope, "a80_secret", settingsNow).also { calls += "token $name $scope" }
    override suspend fun revokeToken(id: Long) = true.also { calls += "revoke $id" }
    override suspend fun removePasskey(id: String) = true.also { calls += "passkey-remove $id" }
    override suspend fun deleteData() = true.also { calls += "wipe" }
    override suspend fun deleteAccount() = true.also { calls += "delete-account" }
    override fun message(text: String) { messages += text }
}
