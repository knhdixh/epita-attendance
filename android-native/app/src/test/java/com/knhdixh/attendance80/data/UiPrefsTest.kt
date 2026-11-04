package com.knhdixh.attendance80.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UiPrefsTest {
    private val dir = Files.createTempDirectory("uiprefs").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("ui.preferences_pb") }
    private val prefs = UiPrefs(store)
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun calendarChoicesComeBackPerAccount() = runBlocking {
        assertNull(prefs.calendar("a@example.com").first())
        prefs.saveCalendar("a@example.com", UiPrefs.Calendar("week", mapOf("sessions" to true, "countdowns" to false)))
        assertEquals(UiPrefs.Calendar("week", mapOf("sessions" to true, "countdowns" to false)), prefs.calendar("A@Example.com").first())
        assertNull("another account sees nothing", prefs.calendar("b@example.com").first())
    }
    @Test fun anEmptyViewIsKept() = runBlocking {
        prefs.saveCalendar("a@example.com", UiPrefs.Calendar("", mapOf("sessions" to true)))
        assertEquals("", prefs.calendar("a@example.com").first()!!.view)
    }
    @Test fun dismissedExamsComeBackPerAccountAndAreCapped() = runBlocking {
        prefs.saveDismissed("a@example.com", (1L..250L).toList())
        assertEquals((51L..250L).toList(), prefs.dismissed("a@example.com").first())
        assertEquals(emptyList<Long>(), prefs.dismissed("b@example.com").first())
    }
    @Test fun projectViewsComeBackPerProjectAndAccount() = runBlocking {
        assertNull(prefs.projectView("a@example.com", 5).first())
        prefs.saveProjectView("a@example.com", 5, UiPrefs.ProjectView("date", "label", true))
        prefs.saveProjectView("a@example.com", 6, UiPrefs.ProjectView("priority", "none", false))
        // A new UiPrefs on the same store is what a new view model sees.
        val again = UiPrefs(store)
        assertEquals(UiPrefs.ProjectView("date", "label", true), again.projectView("A@example.com", 5).first())
        assertEquals(UiPrefs.ProjectView("priority", "none", false), prefs.projectView("a@example.com", 6).first())
        assertNull(prefs.projectView("b@example.com", 5).first())
    }
    @Test fun theStoreIsLeftOutOutOfBackups() {
        val rules = listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml").map { java.io.File(it).readText() }
        rules.forEach { assertTrue(it.contains("datastore/ui_prefs.preferences_pb")) }
    }
}
