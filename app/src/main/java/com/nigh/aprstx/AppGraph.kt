package com.nigh.aprstx

import android.content.Context

/** Process-wide stores so BeaconService and UI share the same StateFlows. */
object AppGraph {
    @Volatile
    private var ready = false
    lateinit var settings: SettingsStore
        private set
    lateinit var logs: LogStore
        private set

    fun init(context: Context) {
        if (!ready) {
            synchronized(this) {
                if (!ready) {
                    val app = context.applicationContext
                    settings = SettingsStore(app)
                    logs = LogStore(app)
                    ready = true
                }
            }
        }
        WifiAutoBeacon.ensureListening(context.applicationContext)
    }
}
