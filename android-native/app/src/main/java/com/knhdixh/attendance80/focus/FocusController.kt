package com.knhdixh.attendance80.focus

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.logic.FinishResult
import com.knhdixh.attendance80.logic.FocusLogEntry
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.logic.adjust
import com.knhdixh.attendance80.logic.finish
import com.knhdixh.attendance80.logic.isDue
import com.knhdixh.attendance80.logic.pause
import com.knhdixh.attendance80.logic.resume
import com.knhdixh.attendance80.logic.setLength
import com.knhdixh.attendance80.logic.setRemaining
import com.knhdixh.attendance80.logic.skipIdle
import com.knhdixh.attendance80.logic.start
import com.knhdixh.attendance80.logic.stop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Sets (or, with null, cancels) the one alarm that ends the running phase. */
interface PhaseAlarm { fun schedule(endsAt: Long?) }

/** The two notifications: the running timer, and "phase done". */
interface FocusNotifier {
    fun showTimer(t: FocusTimer, settings: FocusSettings, now: Long)
    fun phaseDone(ended: String, label: String?, next: FocusTimer, settings: FocusSettings)
}

/** Redraws everything that shows the timer apart from the notification: the quick-settings tile and the widget. */
interface FocusSurfaces {
    fun refresh()
    /** The timer as it is now, for surfaces that keep their own copy (the widget). */
    fun timerChanged(timer: FocusTimer, settings: FocusSettings, now: Long) {}
}

/** Asks for finished sessions to be sent to the server when there is a network. */
interface OutboxScheduler { fun requestFlush() }

/**
 * Every change to the timer goes through here, whoever makes it (the screen, the notification, the tile, the widget
 * or the alarm): it is stamped, saved, the end of the phase is scheduled as an exact alarm, and the notification, tile
 * and widget are redrawn. This is the only timer on the device.
 */
class FocusController(
    private val store: FocusStore,
    private val clock: Clock,
    private val alarm: PhaseAlarm,
    private val notifier: FocusNotifier,
    private val surfaces: FocusSurfaces,
    private val outbox: OutboxScheduler,
    /** Runs once before the first read of the store, whichever entry point gets there first (the TWA migration). */
    private val beforeFirstLoad: suspend () -> Unit = {},
    private val newRun: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val _timer = MutableStateFlow(FocusTimer())
    val timer: StateFlow<FocusTimer> = _timer.asStateFlow()
    private val _settings = MutableStateFlow(FocusSettings())
    val settings: StateFlow<FocusSettings> = _settings.asStateFlow()
    @Volatile private var loaded = false

    /** Reads what was saved. Everything else calls this first, so a receiver in a cold process is safe. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        beforeFirstLoad()
        _timer.value = store.timer()
        _settings.value = store.settings()
        loaded = true
    }

    suspend fun load() = mutex.withLock { ensureLoaded() }

    /** Settings come from `focus.settings` in the snapshot; "Changes apply from the next phase". */
    suspend fun updateSettings(s: FocusSettings) = mutex.withLock {
        ensureLoaded()
        if (s == _settings.value) return@withLock
        _settings.value = s
        store.saveSettings(s)
        render(_timer.value)
    }

    suspend fun start(target: FocusTarget? = null, minutes: Int? = null) = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        var t = _timer.value
        if (t.running()) return@withLock
        if (target != null) t = t.copy(target = target)
        if (minutes != null) t = setLength(t, _settings.value, minutes)
        change(start(t, _settings.value, now, newRun()), null, now)
    }

    /** "Focus on this": a focus already running on something else is stopped (and recorded) first, then this one starts. */
    suspend fun focusOn(target: FocusTarget) = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        var t = _timer.value
        val same = t.running() && t.phase == "focus" && t.target?.homework_id == target.homework_id && t.target?.label == target.label
        if (same) { if (t.paused()) change(resume(t, now), null, now); return@withLock }
        if (t.running()) { val r = stop(t, now); change(r.next, r.log, now); t = _timer.value }
        change(start(t.copy(target = target, next = "focus", custom = null), _settings.value, now, newRun()), null, now)
    }

    suspend fun pause() = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        change(pause(_timer.value, now), null, now)
    }

    suspend fun resume() = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        change(resume(_timer.value, now), null, now)
    }

    /** Start when idle, resume when paused, pause when running: the tile and notification button. */
    suspend fun toggle() {
        load()
        // A phase that ended while nobody was looking is finished (and logged) before the toggle is applied to what is next.
        if (isDue(_timer.value, clock.now())) phaseEnded()
        val t = _timer.value
        when {
            !t.running() -> start()
            t.paused() -> resume()
            else -> pause()
        }
    }

    /** Idle: skip the break that is next. Running: end the phase now, which logs a focus phase as stopped early. */
    suspend fun skip() = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        val t = _timer.value
        if (!t.running()) { change(skipIdle(t), null, now); return@withLock }
        val r = finish(t, _settings.value, now, newRun(), early = true)
        change(r.next, r.log, now)
    }

    suspend fun stop() = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        val t = _timer.value
        if (!t.running()) return@withLock
        val r = stop(t, now)
        change(r.next, r.log, now)
    }

    /** The account whose sessions and tasks the timer records, from the snapshot. */
    suspend fun setAccount(email: String?) = mutex.withLock { store.setAccount(email) }

    /**
     * Signed out (button, 401 or account deletion): a running timer is stopped and recorded for the account that
     * started it, the target is cleared, and the account is forgotten, so nothing recorded from now on can reach
     * whoever signs in next. What was queued stays with its account.
     */
    suspend fun signedOut() = mutex.withLock {
        ensureLoaded()
        if (store.account() == null) return@withLock
        val now = clock.now()
        val t = _timer.value
        if (t.running()) { val r = stop(t, now); change(r.next, r.log, now) }
        change(_timer.value.copy(target = null), null, now)
        store.setAccount(null)
    }

    /** Moves the end of a running phase, or the length the next one will have. */
    suspend fun adjust(min: Int) = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        change(adjust(_timer.value, _settings.value, min, now), null, now)
    }

    /** Idle: the length of the next phase. Running: the time left. */
    suspend fun setLength(min: Int) = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        val t = _timer.value
        change(if (t.running()) setRemaining(t, _settings.value, min * 60_000L, now) else setLength(t, _settings.value, min), null, now)
    }

    /** What you are working on, kept while idle and for the next phases. */
    suspend fun setTarget(target: FocusTarget?) = mutex.withLock {
        ensureLoaded()
        change(_timer.value.copy(target = target), null, clock.now())
    }

    /** The alarm: end the phase, start the next one if the settings say so, and say so. */
    suspend fun phaseEnded() = mutex.withLock {
        ensureLoaded()
        val now = clock.now()
        val t = _timer.value
        if (!isDue(t, now)) { render(t); return@withLock }
        val s = _settings.value
        val r: FinishResult = finish(t, s, now, newRun())
        change(r.next, r.log, now)
        if (s.notify) notifier.phaseDone(t.phase, t.target?.label, r.next, s)
    }

    /** After boot, an update, or a change of time or time zone: finish what is due, or re-arm what is not. */
    suspend fun restore() {
        load()
        val t = _timer.value
        if (isDue(t, clock.now())) phaseEnded() else mutex.withLock { render(_timer.value) }
    }

    /** A timer imported from the old app (Phase 5, task 7). Only taken when we have no running timer of our own. */
    suspend fun adopt(t: FocusTimer) = mutex.withLock {
        ensureLoaded()
        if (_timer.value.running() || !t.running()) return@withLock
        _timer.value = t
        store.saveTimer(t)
        render(t)
    }

    private suspend fun change(next: FocusTimer, log: FocusLogEntry?, now: Long) {
        val t = if (next == _timer.value) next else next.copy(updatedAt = now)
        _timer.value = t
        store.saveTimer(t)
        // A session belongs to the signed-in account. With none (signed out) there is nobody to record it for.
        // The adopted TWA run is the exception: it was started before any sign-in, so it is stored unowned like the TWA's
        // other items, and the first account to sign in claims it.
        if (log != null) {
            val owner = store.account()
            if (owner != null || log.client_id == store.legacyRun()) { store.addLogs(listOf(log), owner); outbox.requestFlush() }
        }
        render(t)
    }

    private fun render(t: FocusTimer) {
        alarm.schedule(if (t.running() && !t.paused()) t.endsAt else null)
        notifier.showTimer(t, _settings.value, clock.now())
        surfaces.timerChanged(t, _settings.value, clock.now())
        surfaces.refresh()
    }
}
