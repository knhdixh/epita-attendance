package com.knhdixh.attendance80.ui.settings

import android.app.Activity
import com.knhdixh.attendance80.AppContainer
import com.knhdixh.attendance80.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What Settings can do. Each call reports its own success or error message, as the web's toasts do. */
interface SettingsActions {
    suspend fun saveRules(threshold: Double, unit: String): Boolean = false
    suspend fun saveNotify(body: JsonObject): Boolean = false
    /** The reminder switches that belong to this device, not the account. */
    val deviceNotify: kotlinx.coroutines.flow.Flow<com.knhdixh.attendance80.notify.NotifyChoices> get() = kotlinx.coroutines.flow.flowOf(com.knhdixh.attendance80.notify.NotifyChoices())
    suspend fun saveDeviceNotify(choices: com.knhdixh.attendance80.notify.NotifyChoices) {}
    suspend fun addLink(url: String, name: String, from: String?): Boolean = false
    suspend fun uploadFile(ics: String, fileName: String, replaceId: Long?): Boolean = false
    suspend fun editCalendar(id: Long, name: String, attendance: Boolean, from: String?): Boolean = false
    suspend fun syncCalendar(id: Long): Boolean = false
    suspend fun removeCalendar(id: Long): Boolean = false
    suspend fun rotateFeed(): Boolean = false
    suspend fun gcalResync(quiet: Boolean = false): Boolean = false
    suspend fun gcalDisconnect(): Boolean = false
    /** A one-time link that opens the Google Calendar connection in the browser, signed in. */
    suspend fun gcalConnectUrl(): String? = null
    suspend fun loadTokens(): List<PersonalTokenDto>? = null
    suspend fun createToken(name: String, scope: String): PersonalTokenCreatedDto? = null
    suspend fun revokeToken(id: Long): Boolean = false
    suspend fun addPasskey(activity: Activity): Boolean = false
    suspend fun removePasskey(id: String): Boolean = false
    suspend fun deleteData(): Boolean = false
    suspend fun deleteAccount(): Boolean = false
    fun message(text: String) {}
}

object NoSettingsActions : SettingsActions

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "12 sessions (3 new, 1 updated)", as importSummary in public/calendars-ui.js. */
fun importSummary(r: CalendarImportSummary): String {
    val total = r.total ?: 0
    if (r.unchanged == true) return "${plural(total, "session")} (no changes)"
    val parts = mutableListOf("${r.added ?: 0} new", "${r.updated ?: 0} updated")
    if ((r.removed ?: 0) > 0) parts += "${r.removed} removed"
    if ((r.recurringIgnored ?: 0) > 0) parts += "${r.recurringIgnored} recurring only imported once"
    return "${plural(total, "session")} (${parts.joinToString(", ")})"
}

private val GCAL_ERRORS = mapOf(
    "scope" to "Calendar access was not granted. Tick the calendar permission on the Google screen.",
    "refresh" to "Google did not grant offline access. Please try again.",
    "config" to "Google Calendar sync is not configured on this server.",
    "access_denied" to "Google Calendar connection was cancelled.",
    "state" to "The connection expired. Please try again.",
)
fun gcalErrorMessage(code: String): String = GCAL_ERRORS[code] ?: "Could not connect Google Calendar."

/** The account-bound implementation: every call is dropped when [owner] is no longer the signed-in session. */
class AccountSettingsActions(private val container: AppContainer, private val owner: String) : SettingsActions {
    private val repo get() = container.dataRepository
    private val api get() = container.api
    private fun current() = repo.sessionSnapshot.value.first == owner
    override fun message(text: String) { repo.reportMessage(owner, text) }

    /** Calls that don't go through the snapshot (tokens, links, passkeys). */
    private suspend fun <T> call(block: suspend () -> T): T? {
        if (!current()) return null
        return try { block().takeIf { current() } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (current()) message(e.message ?: "Request failed")
            null
        }
    }

    private suspend fun calendarChange(request: suspend () -> CalendarImportSummary, done: (CalendarImportSummary) -> String): Boolean {
        var result: CalendarImportSummary? = null
        val ok = repo.mutate({ it }, { request().also { result = it } }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) result?.let { message(done(it)) }
        return ok
    }

    override suspend fun saveRules(threshold: Double, unit: String): Boolean {
        val ok = repo.mutate({ d -> d.copy(user = d.user.copy(threshold = threshold, unit = unit)) },
            { api.updateSettings(SettingsUpdateRequest(threshold, unit)) }, { d, _ -> d }, expectedOwner = owner)
        if (ok) message("Settings saved.")
        return ok
    }

    override suspend fun saveNotify(body: JsonObject): Boolean {
        val withTz = JsonObject(body + ("tz" to kotlinx.serialization.json.JsonPrimitive(container.clock.zone().id)))
        return repo.mutate({ it }, { api.saveNotifySettings(withTz) },
            { d, r -> d.copy(notifications = d.notifications.copy(settings = r.settings, devices = r.devices)) }, expectedOwner = owner)
    }

    override val deviceNotify get() = container.notifyStore?.choices ?: super.deviceNotify
    override suspend fun saveDeviceNotify(choices: com.knhdixh.attendance80.notify.NotifyChoices) {
        val store = container.notifyStore ?: return
        store.setCheckin(choices.checkin); choices.homework?.let { store.setHomework(it) }; store.setExam(choices.exam)
    }

    override suspend fun addLink(url: String, name: String, from: String?) =
        calendarChange({ api.addCalendar(CalendarAddRequest("link", url = url.trim(), name = name.trim(), from = from?.ifBlank { null })) }) { "Added calendar: ${importSummary(it)}." }

    // A file with the same name as an uploaded calendar is a newer version of it, as on the web.
    override suspend fun uploadFile(ics: String, fileName: String, replaceId: Long?): Boolean {
        if (!ics.contains("BEGIN:VCALENDAR")) { message("That does not look like an .ics calendar file."); return false }
        val calendars = repo.sessionSnapshot.value.second?.calendars.orEmpty()
        val id = replaceId ?: calendars.find { it.kind == "file" && it.fileName == fileName }?.id
        val name = calendars.find { it.id == id }?.name
        return calendarChange({
            if (id != null) api.updateCalendarFile(id, CalendarFileRequest(ics, fileName))
            else api.addCalendar(CalendarAddRequest("file", ics = ics, fileName = fileName))
        }) { "${if (id != null) "Updated $name" else "Added calendar"}: ${importSummary(it)}." }
    }

    override suspend fun editCalendar(id: Long, name: String, attendance: Boolean, from: String?): Boolean {
        val link = repo.sessionSnapshot.value.second?.calendars?.find { it.id == id }?.kind == "link"
        val body = buildJsonObject {
            put("name", name.trim()); put("attendance", attendance)
            if (link) put("from", from?.ifBlank { null })
        }
        return calendarChange({ api.updateCalendar(id, body) }) { if (it.total != null) "Saved and updated: ${importSummary(it)}." else "Saved." }
    }

    override suspend fun syncCalendar(id: Long): Boolean {
        if (!current()) return false
        val name = repo.sessionSnapshot.value.second?.calendars?.find { it.id == id }?.name
        return try {
            val r = api.syncCalendar(id)
            if (!current()) return false
            repo.refresh()
            message("Updated $name: ${importSummary(r)}.")
            true
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // A failed update is logged on the calendar: reload to show it, then the error (a reload clears messages).
            if (current()) { repo.refresh(); message(e.message ?: "Request failed") }
            false
        }
    }

    override suspend fun removeCalendar(id: Long): Boolean {
        val name = repo.sessionSnapshot.value.second?.calendars?.find { it.id == id }?.name
        var removed = 0
        val ok = repo.mutate({ it }, { api.deleteCalendar(id).also { removed = it.removed } }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) message("Removed $name (${plural(removed, "session")}).")
        return ok
    }

    override suspend fun rotateFeed(): Boolean {
        val ok = repo.mutate({ it }, { api.rotateFeed() }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) message("New feed link created.")
        return ok
    }

    override suspend fun gcalResync(quiet: Boolean): Boolean {
        var synced = 0
        var failed = 0
        val ok = repo.mutate({ it }, {
            do {
                val r = api.resyncGcal()
                synced += r.synced; failed += r.failed ?: 0
            } while (r.remaining > 0 && r.synced > 0)
        }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) when {
            failed > 0 -> message("$failed homework item(s) could not be synced. Check Settings.")
            !quiet || synced > 0 -> message(if (synced > 0) "Synced $synced homework item(s) to Google Calendar." else "Google Calendar is up to date.")
        }
        return ok
    }

    override suspend fun gcalDisconnect(): Boolean {
        val ok = repo.mutate({ it }, { api.disconnectGcal() }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) message("Google Calendar disconnected.")
        return ok
    }

    override suspend fun gcalConnectUrl(): String? =
        call { api.createWebLink(WebLinkRequest("/auth/google/calendar?app=1")).url }?.let { container.baseUrl.trimEnd('/') + it }

    override suspend fun loadTokens() = call { api.listTokens().tokens }
    override suspend fun createToken(name: String, scope: String) = call { api.createToken(TokenCreateRequest(name.trim(), scope)) }
    override suspend fun revokeToken(id: Long): Boolean = (call { api.deleteToken(id) } != null).also { if (it) message("Token revoked.") }

    override suspend fun addPasskey(activity: Activity): Boolean {
        if (!current()) return false
        // Signed in, the server adds the passkey to this account and returns no new session.
        val result = container.authRepository.registerPasskey(activity, "", bearer = owner)
        if (!current()) return false
        return result.fold({ repo.refresh(); message("Passkey added."); true }, { message(it.message ?: "Request failed"); false })
    }

    override suspend fun removePasskey(id: String): Boolean {
        val ok = repo.mutate({ d -> d.copy(account = d.account.copy(passkeys = d.account.passkeys.filterNot { it.id == id })) },
            { api.deletePasskey(id) }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) message("Passkey removed.")
        return ok
    }

    override suspend fun deleteData(): Boolean {
        val ok = repo.mutate({ it }, { api.deleteData() }, { d, _ -> d }, reload = true, expectedOwner = owner)
        if (ok) message("All data deleted.")
        return ok
    }

    override suspend fun deleteAccount(): Boolean {
        val email = repo.sessionSnapshot.value.second?.user?.email
        if (call { api.deleteAccount(AccountDeleteRequest()) } == null) return false
        // The account is gone: so are its queued sessions and tasks, and its timer.
        container.focusController?.signedOut() // stops a running timer, which records one last session for the account...
        container.focusStore?.let { s -> email?.let { s.purgeAccount(it) } } // ...and then everything of the account goes
        container.authRepository.logout()
        repo.start() // the token is gone: drops the cached snapshot of the deleted account
        return true
    }
}
