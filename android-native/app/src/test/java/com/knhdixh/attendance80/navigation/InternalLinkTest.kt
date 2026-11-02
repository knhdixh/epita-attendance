package com.knhdixh.attendance80.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternalLinkTest {
    @Test fun acceptsOnlySupportedDestinations() {
        listOf("today", "focus", "homework", "homework/new", "session/42", "course/7")
            .forEach { assertEquals(it, internalRoute("a80://$it")) }
        listOf("a80://session/0", "a80://session/-1", "a80://course/1/extra",
            "a80://today?next=focus", "a80://today#focus", "a80://user@today",
            "com.knhdixh.attendance80:/auth", "https://today", "a80://unknown")
            .forEach { assertNull(it, internalRoute(it)) }
    }
}
