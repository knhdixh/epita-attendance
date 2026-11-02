package com.knhdixh.attendance80.focus

import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.ui.focus.TimerActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The timer panel's actions, applied through the controller. */
class ControllerActions(
    private val controller: FocusController,
    private val store: FocusStore,
    private val scope: CoroutineScope,
    private val outbox: OutboxScheduler,
    private val after: () -> Unit = {},
) : TimerActions {
    override fun toggle() { scope.launch { controller.toggle(); after() } }
    override fun skip() { scope.launch { controller.skip(); after() } }
    override fun stop() { scope.launch { controller.stop(); after() } }
    override fun adjust(min: Int) { scope.launch { controller.adjust(min) } }
    override fun setLength(min: Int) { scope.launch { controller.setLength(min) } }
    override fun setTarget(target: FocusTarget?) { scope.launch { controller.setTarget(target) } }

    override fun focusOn(target: FocusTarget) { scope.launch { controller.focusOn(target); after() } }

    /** Saved on the device first, so it works offline; the outbox worker sends it to the account. */
    override fun addTask(label: String) {
        scope.launch {
            val pending = store.pendingTasks()
            if (pending.none { it.equals(label, ignoreCase = true) }) store.savePendingTasks(listOf(label) + pending)
            controller.setTarget(FocusTarget(label = label, tags = controller.timer.value.target?.tags.orEmpty()))
            outbox.requestFlush()
        }
    }
}

/** Asks for the notification permission (once, in context) just before a timer starts. */
class AskingActions(private val inner: TimerActions, private val ask: () -> Unit) : TimerActions by inner {
    override fun toggle() { ask(); inner.toggle() }
    override fun focusOn(target: FocusTarget) { ask(); inner.focusOn(target) }
}
