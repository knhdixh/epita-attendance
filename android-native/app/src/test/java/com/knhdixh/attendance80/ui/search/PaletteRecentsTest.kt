package com.knhdixh.attendance80.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.data.PaletteRecents
import com.knhdixh.attendance80.ui.screenshotData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaletteRecentsTest {
    @Test fun boundedPersistenceAndUnavailableFiltering() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PaletteRecents(context)
        listOf("missing", "nav:today", "nav:focus", "nav:homework", "nav:habits", "nav:courses").forEach { store.record(it) }
        store.record("missing")
        val persisted = PaletteRecents(context).ids.first()
        assertEquals(listOf("missing", "nav:courses", "nav:habits", "nav:homework", "nav:focus"), persisted)
        val recent = searchPalette(screenshotData(), "", persisted).filter { it.section == "Recent" }
        assertEquals(listOf("nav:courses", "nav:habits", "nav:homework"), recent.map { it.id })
    }
}
