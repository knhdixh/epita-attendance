package com.knhdixh.attendance80.background

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RefreshWorkerTest {
    @Test fun periodicRefreshRequiresNetworkEveryHour() {
        val work = refreshRequest().workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(60), work.intervalDuration)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }
}
