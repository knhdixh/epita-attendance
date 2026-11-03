package com.knhdixh.attendance80.notify

import org.junit.Assert.*
import org.junit.Test

class StaleRefreshTest {
    private val now = 1_000_000_000L
    @Test fun neverSyncedIsStale() = assertTrue(NotifyReceiver.isStale(null, now))
    @Test fun freshEnoughIsNot() = assertFalse(NotifyReceiver.isStale(now - 9 * 60_000, now))
    @Test fun olderThanTenMinutesIs() = assertTrue(NotifyReceiver.isStale(now - 11 * 60_000, now))
}
