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

enum class WifiStopArm { NEED_DISCONNECT, ARM_PENDING, ARMED }

enum class WifiStopEvent { CONNECTED, DISCONNECTED, ARM_TIMEOUT }

data class WifiStopResult(val arm: WifiStopArm, val stop: Boolean)

fun wifiStopStep(
    arm: WifiStopArm,
    event: WifiStopEvent,
    autoStop: Boolean,
    beaconActive: Boolean,
): WifiStopResult = when (event) {
    WifiStopEvent.CONNECTED -> WifiStopResult(
        arm = if (arm == WifiStopArm.ARMED) WifiStopArm.ARMED else WifiStopArm.NEED_DISCONNECT,
        stop = arm == WifiStopArm.ARMED && autoStop && beaconActive,
    )
    WifiStopEvent.DISCONNECTED -> WifiStopResult(
        arm = if (arm == WifiStopArm.ARMED) WifiStopArm.ARMED else WifiStopArm.ARM_PENDING,
        stop = false,
    )
    WifiStopEvent.ARM_TIMEOUT -> WifiStopResult(
        arm = if (arm == WifiStopArm.ARM_PENDING) WifiStopArm.ARMED else arm,
        stop = false,
    )
}

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
 * Disconnect + autoStart → wait one minInterval then BeaconService.start;
 * if listening starts connected, disconnect continuously for 100s before connect + autoStop is armed.
 * ponytail: no Application FGS — dies with process.
 */
object WifiAutoBeacon {
    private const val STOP_ARM_DELAY_MS = 100_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pendingStart: Job? = null
    private var pendingStopArm: Job? = null
    private var appContext: Context? = null
    private val wifiNetworks = mutableSetOf<Network>()
    private var stopArm = WifiStopArm.ARMED

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
        wifiNetworks.clear()
        cm.activeNetwork?.let { network ->
            if (cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) {
                wifiNetworks += network
            }
        }
        val initiallyConnected = wifiNetworks.isNotEmpty()
        stopArm = if (initiallyConnected) WifiStopArm.NEED_DISCONNECT else WifiStopArm.ARMED
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onWifiAvailable(network)
            override fun onLost(network: Network) = onWifiLost(network)
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
        wifiNetworks.clear()
        pendingStopArm?.cancel()
        pendingStopArm = null
        stopArm = WifiStopArm.ARMED
    }

    private fun cancelPending() {
        pendingStart?.cancel()
        pendingStart = null
    }

    @Synchronized
    private fun onWifiAvailable(network: Network) {
        if (!wifiNetworks.add(network)) return
        pendingStopArm?.cancel()
        pendingStopArm = null
        val ctx = appContext ?: return
        val settings = AppGraph.settings
        val stopResult = wifiStopStep(
            arm = stopArm,
            event = WifiStopEvent.CONNECTED,
            autoStop = settings.autoStopOnWifiConnect,
            beaconActive = BeaconRuntime.active.value,
        )
        stopArm = stopResult.arm
        if (stopResult.stop) {
            cancelPending()
            AppGraph.logs.add("WiFi connected — auto-stopping schedule", LogType.INFO)
            BeaconService.stop(ctx)
        } else {
            cancelPending()
        }
    }

    @Synchronized
    private fun onWifiLost(network: Network) {
        if (!wifiNetworks.remove(network) || wifiNetworks.isNotEmpty()) return
        val stopResult = wifiStopStep(
            arm = stopArm,
            event = WifiStopEvent.DISCONNECTED,
            autoStop = false,
            beaconActive = false,
        )
        stopArm = stopResult.arm
        if (stopArm == WifiStopArm.ARM_PENDING) {
            pendingStopArm?.cancel()
            pendingStopArm = scope.launch {
                delay(STOP_ARM_DELAY_MS)
                armStopIfStillDisconnected()
            }
        }
        onWifiDisconnected()
    }

    @Synchronized
    private fun armStopIfStillDisconnected() {
        if (wifiNetworks.isNotEmpty()) return
        stopArm = wifiStopStep(
            arm = stopArm,
            event = WifiStopEvent.ARM_TIMEOUT,
            autoStop = false,
            beaconActive = false,
        ).arm
        pendingStopArm = null
        if (stopArm == WifiStopArm.ARMED && AppGraph.settings.autoStopOnWifiConnect) {
            AppGraph.logs.add("WiFi auto-stop armed after 100s disconnected", LogType.INFO)
        }
    }

    private fun onWifiDisconnected() {
        val ctx = appContext ?: return
        val settings = AppGraph.settings
        when (
            wifiAutoAction(
                wifiGained = false,
                autoStart = settings.autoStartOnWifiDisconnect,
                autoStop = false,
                beaconActive = BeaconRuntime.active.value,
            )
        ) {
            WifiAutoAction.SCHEDULE_START -> {
                cancelPending()
                val intervalSec = settings.minIntervalSec
                AppGraph.logs.add(
                    "WiFi disconnected — auto-start in ${intervalSec}s",
                    LogType.INFO,
                )
                pendingStart = scope.launch {
                    delay(intervalSec * 1000L)
                    tryStart(ctx)
                }
            }
            WifiAutoAction.CANCEL_PENDING, WifiAutoAction.STOP, WifiAutoAction.NONE -> Unit
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
