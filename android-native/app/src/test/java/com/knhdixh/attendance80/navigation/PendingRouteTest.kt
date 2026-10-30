package com.knhdixh.attendance80.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class PendingRouteTest {
    @Test fun survivesSignInAndRestorationUntilConsumed() {
        val saved = SavedStateHandle()
        val beforeSignIn = PendingRouteViewModel(saved)
        beforeSignIn.accept("a80://session/42")
        beforeSignIn.accept("com.knhdixh.attendance80:/auth?code=x")
        assertEquals("session/42", beforeSignIn.pending.value)
        val restored = PendingRouteViewModel(saved)
        assertEquals("session/42", restored.pending.value)
        restored.clear()
        assertNull(PendingRouteViewModel(saved).pending.value)
    }
}
