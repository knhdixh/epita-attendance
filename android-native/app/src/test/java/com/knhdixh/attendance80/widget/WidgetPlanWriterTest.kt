package com.knhdixh.attendance80.widget

import com.knhdixh.attendance80.auth.TokenStore
import com.knhdixh.attendance80.focus.FakeClock
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** The collector's decision about a null snapshot, with a token store that loads late like the real one. */
class WidgetPlanWriterTest {
    @get:Rule val folder = TemporaryFolder()

    private class LateTokens(initial: String?) : TokenStore {
        val loaded = CompletableDeferred<Unit>()
        private val _token = MutableStateFlow<String?>(null)
        private val value = initial
        private val _isLoaded = MutableStateFlow(false)
        override val token: StateFlow<String?> = _token
        override val isLoaded: StateFlow<Boolean> = _isLoaded
        fun load() { _token.value = value; _isLoaded.value = true; loaded.complete(Unit) }
        override suspend fun awaitToken(): String? { loaded.await(); return _token.value }
        override suspend fun setToken(token: String) { _token.value = token }
        override suspend fun clearToken() { _token.value = null }
    }

    private val fixture: AppData = ApiClient.json.decodeFromString(javaClass.getResource("/api/data.json")!!.readText())
    private val a: AppData = fixture.copy(events = fixture.events.map { it.copy(status = null) })
    private val b: AppData = a.copy(
        courses = a.courses.map { it.copy(name = "Bravo " + it.name) },
        homework = a.homework.map { it.copy(title = "B " + it.title) })
    private val clock = FakeClock(Instant.parse("2026-11-02T07:00:00Z").toEpochMilli())
    private fun store() = WidgetStore(folder.newFile().also { it.delete() })

    private fun titles(s: WidgetSnapshot) = s.classes.map { it.title } + s.todo.map { it.title }

    @Test fun coldStartWithSavedTokenNeverWritesSignedOut() = runBlocking {
        val tokens = LateTokens("token-a")
        val widget = store()
        val writer = WidgetPlanWriter(tokens, widget, clock) {}
        val early = async { writer.onSnapshot(null) } // before the token store has loaded
        tokens.load()
        early.await()
        assertTrue("never signed out", widget.read().signedIn) // an empty signed-in plan, not "Open the app to sign in"
        writer.onSnapshot(a) // then the cache arrives
        assertTrue(widget.read().signedIn)
        assertTrue(widget.read().classes.isNotEmpty())
        // Next cold start: the stored plan is this account's, so a null snapshot before the cache loads keeps it.
        val again = WidgetPlanWriter(LateTokens("token-a").also { it.load() }, widget, clock) {}
        again.onSnapshot(null)
        assertTrue(widget.read().signedIn && widget.read().classes.isNotEmpty())
    }

    @Test fun signOutClearsAtOnce() = runBlocking {
        val tokens = LateTokens("token-a").also { it.load() }
        val widget = store()
        val writer = WidgetPlanWriter(tokens, widget, clock) {}
        writer.onSnapshot(a)
        assertTrue(widget.read().classes.isNotEmpty())
        tokens.clearToken()
        writer.onSnapshot(null)
        widget.read().let { assertFalse(it.signedIn); assertTrue(it.classes.isEmpty() && it.todo.isEmpty()) }
    }

    @Test fun switchingFromAToBNeverLeavesAItemsInTheFile() = runBlocking {
        val tokens = LateTokens("token-a").also { it.load() }
        val widget = store()
        val writer = WidgetPlanWriter(tokens, widget, clock) {}
        writer.onSnapshot(a)
        val aTitles = titles(widget.read())
        assertTrue(aTitles.isNotEmpty())
        // B signs in: the repository publishes null (B's cache or refresh is pending, perhaps offline) before B's data.
        tokens.setToken("token-b")
        writer.onSnapshot(null)
        val file = folder.root.listFiles().first { it.length() > 0 }.readText()
        assertTrue(widget.read().signedIn && widget.read().classes.isEmpty() && widget.read().todo.isEmpty())
        aTitles.forEach { assertFalse("$it still in the file", file.contains("\"$it\"")) }
        writer.onSnapshot(b)
        titles(widget.read()).forEach { assertTrue(it, it.startsWith("Bravo") || it.startsWith("B ")) }
    }
}
