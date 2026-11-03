package com.knhdixh.attendance80.notify

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

class NotifyStoreTest {
    private val dir = Files.createTempDirectory("notify-store").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private fun store() = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun defaultsAreOnWithHomeworkUndecided() = runBlocking {
        assertEquals(NotifyChoices(checkin = true, homework = null, exam = true), store().choices.first())
    }

    @Test fun switchesAreRemembered() = runBlocking {
        val s = store()
        s.setCheckin(false); s.setHomework(true); s.setExam(false)
        assertEquals(NotifyChoices(checkin = false, homework = true, exam = false), s.choices.first())
    }

    @Test fun postedKeysAreForgottenAfterThreeDays() = runBlocking {
        val s = store()
        s.markSent(listOf("me|a"), 1_000)
        s.markSent(listOf("me|b"), 1_000 + 2 * 24 * 3600_000L)
        assertEquals(setOf("me|a", "me|b"), s.sent(1_000 + 2 * 24 * 3600_000L))
        assertEquals(setOf("me|b"), s.sent(1_000 + 3 * 24 * 3600_000L + 1))
    }

    @Test fun signOutForgetsThePostedKeys() = runBlocking {
        val s = store()
        s.markSent(listOf("me|a"), 1_000)
        s.signedOut()
        assertEquals(emptySet<String>(), s.sent(1_001))
    }
}
