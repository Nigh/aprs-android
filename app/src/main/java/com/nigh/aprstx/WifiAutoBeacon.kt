package com.nigh.aprstx

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pure WiFi edge → action for auto beacon (unit-tested). */
enum class WifiAutoAction { NONE, SCHEDULE_START, CANCEL_PENDING, STOP }

fun wifiAutoAction(
    wifiGained: Boolean,
    autoStart: Boolean,
    autoStop: Boolean,
    beaconActive: Boolean,
): WifiAutoAction = when {
    wifiGained && autoStop && beaconActive -> WifiAutoAction.STOP
    wifiGained -> WifiAutoAction.CANCEL_PENDING
    !wifiGained && autoStart && !beaconActive -> WifiAutoAction.SCHEDULE_START
    else -> WifiAutoAction.NONE
}

/**
 * Listens for WiFi up/down while process is alive.
 * Disconnect + autoStart → wait one scheduleInterval then BeaconService.start;
 * connect + autoStop → BeaconService.stop. ponytail: no Application FGS — dies with process.
 */
object WifiAutoBeacon {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pendingStart: Job? = null
    private var appContext: Context? = null

    @Synchronized
    fun ensureListening(context: Context) {
        val app = context.applicationContext
        appContext = app
        val settings = AppGraph.settings
        val want = settings.autoStartOnWifiDisconnect || settings.autoStopOnWifiConnect
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!want) {
            unregister(cm)
            cancelPending()
            return
        }
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onWifiEdge(gained = true)
            override fun onLost(network: Network) = onWifiEdge(gained = false)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, cb)
        callback = cb
    }

    private fun unregister(cm: ConnectivityManager) {
        callback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            callback = null
        }
    }

    private fun cancelPending() {
        pendingStart?.cancel()
        pendingStart = null
    }

    private fun onWifiEdge(gained: Boolean) {
        val ctx = appContext ?: return
        val settings = AppGraph.settings
        when (
            wifiAutoAction(
                wifiGained = gained,
                autoStart = settings.autoStartOnWifiDisconnect,
                autoStop = settings.autoStopOnWifiConnect,
                beaconActive = BeaconRuntime.active.value,
            )
        ) {
            WifiAutoAction.CANCEL_PENDING -> cancelPending()
            WifiAutoAction.STOP -> {
                cancelPending()
                AppGraph.logs.add("WiFi connected — auto-stopping schedule", LogType.INFO)
                BeaconService.stop(ctx)
            }
            WifiAutoAction.SCHEDULE_START -> {
                cancelPending()
                val intervalSec = settings.scheduleIntervalSec.coerceAtLeast(Aprs.MIN_INTERVAL_SEC)
                AppGraph.logs.add(
                    "WiFi disconnected — auto-start in ${intervalSec}s",
                    LogType.INFO,
                )
                pendingStart = scope.launch {
                    delay(intervalSec * 1000L)
                    tryStart(ctx)
                }
            }
            WifiAutoAction.NONE -> Unit
        }
    }

    private fun tryStart(ctx: Context) {
        if (BeaconRuntime.active.value) return
        val settings = AppGraph.settings
        if (!settings.autoStartOnWifiDisconnect) return
        val v = Aprs.validateCallsign(settings.callsign, settings.passcode)
        if (!v.valid) {
            val msg = "WiFi auto-start skipped: ${v.message ?: "invalid callsign"}"
            AppGraph.logs.add(msg, LogType.ERROR)
            return
        }
        if (!LocationHelper.hasFineLocation(ctx)) {
            AppGraph.logs.add("WiFi auto-start skipped: location permission required", LogType.ERROR)
            return
        }
        AppGraph.logs.add("WiFi auto-starting schedule", LogType.SUCCESS)
        BeaconService.start(ctx)
    }
}
