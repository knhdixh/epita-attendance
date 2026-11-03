package com.knhdixh.attendance80.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Not synced yet" shows while changes made on the device wait in the outbox, and goes when the last one is sent. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class NotSyncedIndicatorTest {
    @get:Rule val compose = createComposeRule()
    private val empty = ApiClient.json.decodeFromString<com.knhdixh.attendance80.net.AppData>(object {}.javaClass.getResource("/api/data_empty.json")!!.readText())

    @Test fun showsWhileTheOutboxHasChangesAndGoesWhenItIsEmpty() {
        var pending by mutableIntStateOf(0)
        compose.setContent { Attendance80Theme { Shell(ShellState(data = empty, pending = pending), {}, {}, {}, now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nothing due in the next 7 days.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("not-synced").assertDoesNotExist()
        compose.runOnIdle { pending = 2 }
        compose.onNodeWithTag("not-synced").assertExists().assertTextEquals("Not synced yet")
        compose.onNodeWithContentDescription("Not synced yet: 2 changes waiting").assertExists()
        compose.runOnIdle { pending = 1 }
        compose.onNodeWithContentDescription("Not synced yet: 1 change waiting").assertExists()
        compose.runOnIdle { pending = 0 }
        compose.onNodeWithTag("not-synced").assertDoesNotExist()
    }
}
