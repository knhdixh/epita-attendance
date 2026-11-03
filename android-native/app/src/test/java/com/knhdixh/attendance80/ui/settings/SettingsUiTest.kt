package com.knhdixh.attendance80.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.knhdixh.attendance80.net.PersonalTokenDto
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SettingsUiTest {
    @get:Rule val ui = createComposeRule()
    private fun show(actions: SettingsActions, data: com.knhdixh.attendance80.net.AppData = settingsData(), scale: Float = 1f, onSignOut: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme { SettingsScreen(data, data.events, emptyList(), settingsNow, settingsZone, actions, onSignOut, onNavigate, baseUrl = "https://app.test") }
            }
        }
    }
    private fun scrollTo(tag: String) = ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
    private fun scrollToText(text: String) = ui.onNodeWithTag("settings-list").performScrollToNode(hasText(text, substring = true))
    private fun idle(cond: () -> Boolean) = ui.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); cond() }

    @Test fun deviceSwitchesStartOnExceptHomeworkWhileGoogleCalendarIsConnectedAndSaveWhatYouChoose() {
        val actions = FakeSettingsActions()
        var asked = 0
        val data = settingsData()   // Google Calendar connected
        ui.setContent { Attendance80Theme { SettingsScreen(data, data.events, emptyList(), settingsNow, settingsZone, actions, {}, {}, baseUrl = "https://app.test", onAskNotifications = { asked++ }) } }
        scrollTo("notify-checkin"); ui.onNodeWithTag("notify-checkin").assertIsOn()
        scrollTo("notify-exam"); ui.onNodeWithTag("notify-exam").assertIsOn()
        scrollTo("notify-homework"); ui.onNodeWithTag("notify-homework").assertIsOff()
        ui.onNodeWithTag("notify-homework").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("device NotifyChoices(checkin=true, homework=true, exam=true)", actions.calls.single())
        assertEquals(1, asked) // asked in context, when a reminder switch is turned on
        scrollTo("notify-checkin"); ui.onNodeWithTag("notify-checkin").performClick()
        idle { actions.calls.size == 2 }
        assertEquals("device NotifyChoices(checkin=false, homework=true, exam=true)", actions.calls.last())
    }

    private fun showNotifyAsking(actions: SettingsActions, blocked: Boolean, ask: () -> Unit = {}, fix: () -> Unit = {}) {
        val data = settingsData()
        ui.setContent { Attendance80Theme { SettingsScreen(data, data.events, emptyList(), settingsNow, settingsZone, actions, {}, {}, baseUrl = "https://app.test",
            onAskNotifications = ask, notificationsBlocked = blocked, onFixNotifications = fix) } }
    }

    @Test fun blockedNotificationsShowARowThatAsksAgain() {
        var asked = 0; var fixed = 0
        showNotifyAsking(FakeSettingsActions(), blocked = true, ask = { asked++ }, fix = { fixed++ })
        scrollTo("notify-blocked"); ui.onNodeWithTag("notify-blocked").assertIsDisplayed()
        assertEquals(1, asked) // the first in-context ask: Notifications is shown
        ui.onNodeWithTag("notify-allow").performClick()
        assertEquals(1, fixed)
    }

    @Test fun noRowWhenNotificationsAreAllowed() {
        var asked = 0
        showNotifyAsking(FakeSettingsActions(), blocked = false, ask = { asked++ })
        scrollTo("notify-checkin")
        ui.onNodeWithTag("notify-blocked").assertDoesNotExist()
        assertEquals(0, asked)
    }

    @Test fun turningClassesOrHabitsOnAsksButTurningOffDoesNot() {
        var asked = 0
        showNotifyAsking(FakeSettingsActions(), blocked = false, ask = { asked++ })
        scrollTo("notify-habits"); ui.onNodeWithTag("notify-habits").performClick() // on in the fixture: turning off
        assertEquals(0, asked)
        scrollTo("notify-classes"); ui.onNodeWithTag("notify-classes").performClick() // off in the fixture: turning on
        assertEquals(1, asked)
    }

    @Test fun homeworkRemindersAreOnByDefaultWithoutGoogleCalendar() {
        val actions = FakeSettingsActions()
        show(actions, settingsBase())
        scrollTo("notify-homework"); ui.onNodeWithTag("notify-homework").assertIsOn()
    }

    @Test fun noCalendarOpensTheAddBoxAndAddsALink() {
        val actions = FakeSettingsActions()
        show(actions, settingsBase().copy(calendars = emptyList(), calendarSyncs = emptyList()))
        ui.onNodeWithTag("settings-name").assertTextEquals("student")
        scrollTo("calendar-add")
        ui.onNodeWithText("Cancel").assertDoesNotExist()
        ui.onNodeWithTag("calendar-link-url").performTextInput("https://zeus.test/ics")
        ui.onNodeWithTag("calendar-link-name").performTextInput("SE1")
        ui.onNodeWithTag("calendar-link-add").performScrollTo().performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals(listOf("link https://zeus.test/ics|SE1|null"), actions.calls)
        // The Sisu/Zeus guide and the empty-token list still show.
        scrollToText("Claude Code access")
        scrollToText("Not configured on this server.")
    }

    @Test fun rulesNotificationsAndSignOut() {
        val actions = FakeSettingsActions()
        var signedOut = false
        show(actions, onSignOut = { signedOut = true })
        ui.onNodeWithText("Sign out").performClick(); assertTrue(signedOut)
        ui.onNodeWithTag("settings-threshold").performTextReplacement("0")
        scrollTo("settings-rules-save"); ui.onNodeWithTag("settings-rules-save").assertIsNotEnabled()
        ui.onNodeWithTag("settings-threshold").performTextReplacement("75")
        ui.onNodeWithTag("settings-unit-hours").performClick()
        ui.onNodeWithTag("settings-rules-save").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("rules 0.75 hours", actions.calls.removeAt(0))
        scrollTo("notify-habits"); ui.onNodeWithTag("notify-habits").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("""notify {"classes":false,"classLead":30,"habits":false,"focus":true}""", actions.calls.single())
        // Class reminders are off in the fixture, so their lead time can't be changed.
        ui.onNodeWithTag("notify-lead").assertIsNotEnabled()
    }

    @Test fun calendarRowsEditAndRemove() {
        val actions = FakeSettingsActions()
        show(actions)
        scrollTo("calendar-2")
        ui.onNodeWithText("HTTP 503 from zeus.ionis-it.com").assertExists()
        ui.onNodeWithText("zeus.ionis-it.com · 40 sessions").assertExists()
        ui.onNodeWithContentDescription("Update now").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("sync 2", actions.calls.removeAt(0))
        ui.onNodeWithContentDescription("Edit SE1").performClick()
        ui.onNodeWithTag("calendar-edit-name").performTextReplacement("SE2")
        ui.onNodeWithTag("calendar-edit-attendance").performClick()
        ui.onNodeWithTag("calendar-edit-save").performScrollTo().performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("edit 2 SE2 false 2026-09-01", actions.calls.removeAt(0))
        scrollTo("calendar-1")
        ui.onNodeWithContentDescription("Remove epita_classes").performClick()
        // The fixture's sessions are all only in calendar 1.
        ui.onNodeWithText("Remove \"epita_classes\"? 3 sessions only in it, and their attendance marks, are deleted.").assertExists()
        ui.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("remove 1", actions.calls.single())
        scrollTo("sync-log"); ui.onNodeWithTag("sync-log").performClick()
        scrollToText("SE1 · Automatic · HTTP 503")
    }

    @Test fun tokensAreListedCreatedAndShownOnce() {
        val actions = FakeSettingsActions(listOf(PersonalTokenDto(3, "Old laptop", "read", settingsNow - 86400000L)))
        show(actions)
        scrollTo("token-3")
        ui.onNodeWithText("read only", substring = true).assertExists()
        scrollTo("token-name"); ui.onNodeWithTag("token-name").performTextInput("MacBook")
        scrollTo("token-scope-read"); ui.onNodeWithTag("token-scope-read").performClick().assertIsSelected()
        scrollTo("token-create"); ui.onNodeWithTag("token-create").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("token MacBook read", actions.calls.removeAt(0))
        scrollTo("token-fresh"); ui.onNodeWithTag("token-fresh").assertTextEquals("a80_secret")
    }

    @Test fun feedLinksFollowTheGroupsPicked() {
        show(FakeSettingsActions())

        scrollTo("feed-timetable")
        ui.onNodeWithTag("feed-timetable-url").assertTextEquals("https://app.test/feed/NMCP28BlZFLS4I0X12A6xYNKtjPUtlAe/timetable.ics")
        ui.onNodeWithTag("feed-group-2").performClick()
        ui.onNodeWithTag("feed-timetable-url").assertTextEquals("https://app.test/feed/NMCP28BlZFLS4I0X12A6xYNKtjPUtlAe/timetable.ics?groups=2")
        ui.onNodeWithTag("feed-group-1").performClick()
        // Every group picked is the plain link again.
        ui.onNodeWithTag("feed-group-all").assertIsSelected()
    }

    @Test fun googleCalendarAndDangerZone() {
        val actions = FakeSettingsActions()
        var route = ""
        show(actions, onNavigate = { route = it })

        scrollTo("gcal-disconnect")
        ui.onNodeWithText("Google Calendar access was revoked. Reconnect to keep syncing.").assertExists()
        ui.onNodeWithText("Reconnect").assertExists()
        ui.onNodeWithTag("gcal-resync").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("resync", actions.calls.removeAt(0))

        scrollTo("settings-wipe"); ui.onNodeWithTag("settings-wipe").performClick()
        ui.onNodeWithText("Delete all your sessions, attendance marks and breaks? Your 2 calendars are removed too. This cannot be undone.").assertExists()
        ui.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        idle { actions.calls.isNotEmpty() && route.isNotEmpty() }
        assertEquals("wipe", actions.calls.removeAt(0)); assertEquals("today", route)

        scrollTo("settings-delete-account"); ui.onNodeWithTag("settings-delete-account").performClick()
        scrollTo("delete-account-go")
        ui.onNodeWithTag("delete-account-confirm").performTextInput("delete")
        ui.onNodeWithTag("delete-account-go").performClick()
        ui.runOnIdle { assertEquals(listOf("Account not deleted: you need to type DELETE."), actions.messages); assertTrue(actions.calls.isEmpty()) }
        ui.onNodeWithTag("delete-account-confirm").performTextInput("DELETE")
        ui.onNodeWithTag("delete-account-go").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("delete-account", actions.calls.single())
    }

    @Test fun passkeysAndLargeText() {
        val actions = FakeSettingsActions()
        show(actions, scale = 2f)
        scrollTo("passkey-pk_123456789")
        ui.onNodeWithText("synced").assertExists()
        ui.onNodeWithText("Remove").performClick()
        ui.onNodeWithText("Remove this passkey? You won't be able to sign in with it any more.").assertExists()
        ui.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("passkey-remove pk_123456789", actions.calls.single())
        scrollTo("passkey-add"); ui.onNodeWithText("Add a passkey on another device").assertExists()
    }

    @Test fun shellOpensSettings() {
        ui.setContent { Attendance80Theme { Shell(ShellState(data = settingsData(), session = "a"), {}, {}, {}, initialRoute = "settings", now = settingsNow, zone = settingsZone) } }
        ui.onNodeWithTag("settings-list").assertExists()
        ui.onNodeWithTag("shell-title").assertTextEquals("Settings")
    }
}
