package com.knhdixh.attendance80.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
        restored.clearIfMatches("session/41")
        assertEquals("session/42", restored.pending.value)
        restored.clear()
        assertNull(PendingRouteViewModel(saved).pending.value)
    }
}

class ReceiveSharedTest {
    @Test fun aSlowReadIsDeliveredFromTheViewModelScopeAfterTheActivityIsGone() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val vm = PendingRouteViewModel(SavedStateHandle())
            val read = java.util.concurrent.CountDownLatch(1)
            val io = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            vm.receive(io) { read.await(); com.knhdixh.attendance80.share.Incoming.SharedText("Read chapter 3") }
            // The activity may be recreated now; nothing of the read lives in it.
            assertNull(vm.pending.value)
            read.countDown()
            val until = System.currentTimeMillis() + 5_000
            while (vm.pending.value == null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertEquals("homework/new", vm.pending.value)
            assertEquals("Read chapter 3", vm.sharedText.value)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }
    @Test fun nothingToShareDoesNothing() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val vm = PendingRouteViewModel(SavedStateHandle())
            vm.receive(kotlinx.coroutines.Dispatchers.Unconfined) { null }
            assertNull(vm.pending.value)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }
}
