package com.knhdixh.attendance80

import android.content.Context

interface AppContainer {
    val context: Context
    val clock: Clock
}

class DefaultAppContainer(
    override val context: Context,
    override val clock: Clock = SystemClock(),
) : AppContainer
