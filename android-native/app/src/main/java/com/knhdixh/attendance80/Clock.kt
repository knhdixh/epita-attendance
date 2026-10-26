package com.knhdixh.attendance80

import java.time.ZoneId

interface Clock {
    fun now(): Long
    fun zone(): ZoneId
}

class SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
