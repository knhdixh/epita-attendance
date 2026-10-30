package com.knhdixh.attendance80

import android.app.Application
import com.knhdixh.attendance80.background.scheduleRefresh

class App : Application() {
    lateinit var container: AppContainer
        private set

    private var dataStarted = false

    @Synchronized fun startData() {
        if (!dataStarted) {
            dataStarted = true
            (container as DefaultAppContainer).startData()
            scheduleRefresh(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
