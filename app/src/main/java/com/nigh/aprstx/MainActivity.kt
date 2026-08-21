package com.nigh.aprstx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* UI reacts via hasFineLocation checks */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        val settings = AppGraph.settings
        val logs = AppGraph.logs
        BeaconRuntime.setLocation(settings.lastLocation)
        ensureRuntimePermissions()

        setContent {
            val scope = rememberCoroutineScope()
            val active by BeaconRuntime.active.collectAsState()
            val countdown by BeaconRuntime.countdownSec.collectAsState()
            val busy by BeaconRuntime.busy.collectAsState()
            val location by BeaconRuntime.lastLocation.collectAsState()
            val logList by logs.logs.collectAsState()
            val toast by BeaconRuntime.toast.collectAsState()

            val pollInterval by BeaconRuntime.intervalSec.collectAsState()

            var screen by remember { mutableStateOf("main") }
            var callsign by remember { mutableStateOf(settings.callsign) }
            var passcode by remember { mutableStateOf(settings.passcode) }
            var comment by remember { mutableStateOf(settings.commentText) }
            var status by remember { mutableStateOf(settings.statusText) }
            var minInterval by remember { mutableStateOf(settings.minIntervalSec) }
            var maxInterval by remember { mutableStateOf(settings.maxIntervalSec) }
            var smartMove by remember { mutableStateOf(settings.smartMoveEnabled) }
            var moveThreshold by remember { mutableStateOf(settings.moveThresholdM) }

            LaunchedEffect(toast) {
                val t = toast ?: return@LaunchedEffect
                Toast.makeText(this@MainActivity, t.first, Toast.LENGTH_SHORT).show()
                BeaconRuntime.clearToast()
            }

            var autoStartWifi by remember { mutableStateOf(settings.autoStartOnWifiDisconnect) }
            var autoStopWifi by remember { mutableStateOf(settings.autoStopOnWifiConnect) }
            var autoPowerSave by remember { mutableStateOf(settings.autoPowerSaveEnabled) }
            var stopZones by remember { mutableStateOf(settings.stopZones) }

            BackHandler(enabled = screen != "main") {
                screen = "main"
            }

            XianiiTheme {
                // targetSdk 35 edge-to-edge: keep content clear of status/nav bars
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    when (screen) {
                        "logs" -> LogsScreen(
                            logs = logList,
                            onBack = { screen = "main" },
                            onClear = { logs.clear() },
                        )
                        "settings" -> SettingsScreen(
                            autoStartOnWifiDisconnect = autoStartWifi,
                            autoStopOnWifiConnect = autoStopWifi,
                            autoPowerSave = autoPowerSave,
                            minIntervalSec = minInterval,
                            maxIntervalSec = maxInterval,
                            smartMove = smartMove,
                            moveThresholdM = moveThreshold,
                            stopZones = stopZones,
                            onAutoStartOnWifiDisconnect = {
                                autoStartWifi = it
                                settings.autoStartOnWifiDisconnect = it
                                WifiAutoBeacon.ensureListening(this@MainActivity)
                            },
                            onAutoStopOnWifiConnect = {
                                autoStopWifi = it
                                settings.autoStopOnWifiConnect = it
                                WifiAutoBeacon.ensureListening(this@MainActivity)
                            },
                            onAutoPowerSave = {
                                autoPowerSave = it
                                settings.autoPowerSaveEnabled = it
                            },
                            onMinInterval = {
                                settings.minIntervalSec = it
                                minInterval = settings.minIntervalSec
                                maxInterval = settings.maxIntervalSec
                            },
                            onMaxInterval = {
                                settings.maxIntervalSec = it
                                maxInterval = settings.maxIntervalSec
                            },
                            onSmartMove = {
                                settings.smartMoveEnabled = it
                                smartMove = it
                                maxInterval = settings.maxIntervalSec
                            },
                            onMoveThreshold = {
                                settings.moveThresholdM = it
                                moveThreshold = settings.moveThresholdM
                            },
                            onStopZonesChange = {
                                stopZones = it
                                settings.stopZones = it
                            },
                            onFetchGps = {
                                ensureRuntimePermissions()
                                try {
                                    withContext(Dispatchers.IO) {
                                        val prev = settings.lastLocation
                                        LocationHelper.getLocation(this@MainActivity, prev).also { loc ->
                                            settings.lastLocation = loc
                                            BeaconRuntime.setLocation(loc)
                                        }
                                    }
                                } catch (e: Exception) {
                                    val msg = "Failed to get GPS location: ${e.message}"
                                    logs.add(msg, LogType.ERROR)
                                    BeaconRuntime.emitToast(msg, LogType.ERROR)
                                    throw e
                                }
                            },
                            onExportJson = { settings.exportJson() },
                            onImportJson = { raw ->
                                if (!settings.importJson(raw)) return@SettingsScreen false
                                callsign = settings.callsign
                                passcode = settings.passcode
                                comment = settings.commentText
                                status = settings.statusText
                                smartMove = settings.smartMoveEnabled
                                minInterval = settings.minIntervalSec
                                maxInterval = settings.maxIntervalSec
                                moveThreshold = settings.moveThresholdM
                                autoStartWifi = settings.autoStartOnWifiDisconnect
                                autoStopWifi = settings.autoStopOnWifiConnect
                                autoPowerSave = settings.autoPowerSaveEnabled
                                stopZones = settings.stopZones
                                WifiAutoBeacon.ensureListening(this@MainActivity)
                                true
                            },
                            onBack = { screen = "main" },
                        )
                        else -> MainScreen(
                            callsign = callsign,
                            passcode = passcode,
                            comment = comment,
                            status = status,
                            location = location,
                            busy = busy,
                            scheduling = active,
                            countdownSec = countdown,
                            pollIntervalSec = pollInterval,
                            minIntervalSec = minInterval,
                            maxIntervalSec = maxInterval,
                            smartMove = smartMove,
                            moveThresholdM = moveThreshold,
                            onCallsign = {
                                callsign = it
                                settings.callsign = it
                            },
                            onPasscode = {
                                passcode = it
                                settings.passcode = it
                            },
                            onComment = {
                                comment = it
                                settings.commentText = it
                            },
                            onStatus = {
                                status = it
                                settings.statusText = it
                            },
                            onGps = {
                                ensureRuntimePermissions()
                                scope.launch {
                                    BeaconRuntime.setBusy(true)
                                    try {
                                        val prev = settings.lastLocation
                                        val loc = withContext(Dispatchers.IO) {
                                            LocationHelper.getLocation(this@MainActivity, prev)
                                        }
                                        settings.lastLocation = loc
                                        BeaconRuntime.setLocation(loc)
                                        val msg =
                                            "GPS location retrieved: %.4f°, %.4f°".format(loc.latitude, loc.longitude)
                                        logs.add(msg, LogType.SUCCESS)
                                        BeaconRuntime.emitToast(msg, LogType.SUCCESS)
                                    } catch (e: Exception) {
                                        val msg = "Failed to get GPS location: ${e.message}"
                                        logs.add(msg, LogType.ERROR)
                                        BeaconRuntime.emitToast(msg, LogType.ERROR)
                                    } finally {
                                        BeaconRuntime.setBusy(false)
                                    }
                                }
                            },
                            onSend = {
                                val v = Aprs.validateCallsign(callsign, passcode)
                                if (!v.valid) {
                                    logs.add(v.message ?: "Validation failed", LogType.ERROR)
                                    BeaconRuntime.emitToast(v.message ?: "Validation failed", LogType.ERROR)
                                    return@MainScreen
                                }
                                ensureRuntimePermissions()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Transmitter.transmitOnce(
                                            this@MainActivity,
                                            settings,
                                            logs,
                                            "transmission",
                                        )
                                    }
                                }
                            },
                            onStartSchedule = {
                                val v = Aprs.validateCallsign(callsign, passcode)
                                if (!v.valid) {
                                    logs.add(v.message ?: "Validation failed", LogType.ERROR)
                                    BeaconRuntime.emitToast(v.message ?: "Validation failed", LogType.ERROR)
                                    return@MainScreen
                                }
                                ensureRuntimePermissions()
                                if (!LocationHelper.hasFineLocation(this@MainActivity)) {
                                    BeaconRuntime.emitToast("Location permission required", LogType.ERROR)
                                    return@MainScreen
                                }
                                BeaconService.start(this@MainActivity)
                            },
                            onStopSchedule = { BeaconService.stop(this@MainActivity) },
                            onOpenLogs = { screen = "logs" },
                            onOpenSettings = { screen = "settings" },
                        )
                    }
                }
            }
        }
    }

    private fun ensureRuntimePermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }
}
