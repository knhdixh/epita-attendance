package com.knhdixh.attendance80.ui.search

import com.knhdixh.attendance80.ui.screenshotData
import org.junit.Assert.*
import org.junit.Test

class PaletteTest {
    private val data = screenshotData()
    @Test fun searchesEachEntityAndRoutesToItsItem() {
        for ((query, route) in listOf("mathematiques" to "course/1", "amphi" to "session/1", "exercise" to "homework/1", "read" to "habits/1", "exam" to "countdowns/1")) {
            assertTrue(query, searchPalette(data, query).any { it.route == route })
        }
        assertTrue(searchPalette(data, "xyzzy").isEmpty())
    }
    @Test fun homeActionsAndScoringMatchPalette() {
        assertTrue(searchPalette(data, "").any { it.label == "Add homework" })
        assertEquals("logout", searchPalette(data, "log out").single().id)
        assertTrue(searchPalette(data, "routine").any { it.label == "New habit" })
        assertTrue(searchPalette(null, "").isEmpty())
    }
}
