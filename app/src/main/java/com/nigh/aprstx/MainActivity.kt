package com.nigh.aprstx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
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

            var screen by remember { mutableStateOf("main") }
            var callsign by remember { mutableStateOf(settings.callsign) }
            var passcode by remember { mutableStateOf(settings.passcode) }
            var comment by remember { mutableStateOf(settings.commentText) }
            var status by remember { mutableStateOf(settings.statusText) }
            var interval by remember { mutableStateOf(settings.scheduleIntervalSec.toString()) }

            LaunchedEffect(toast) {
                val t = toast ?: return@LaunchedEffect
                Toast.makeText(this@MainActivity, t.first, Toast.LENGTH_SHORT).show()
                BeaconRuntime.clearToast()
            }

            XianiiTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (screen == "logs") {
                        LogsScreen(
                            logs = logList,
                            onBack = { screen = "main" },
                            onClear = { logs.clear() },
                        )
                    } else {
                        MainScreen(
                            callsign = callsign,
                            passcode = passcode,
                            comment = comment,
                            status = status,
                            interval = interval,
                            location = location,
                            busy = busy,
                            scheduling = active,
                            countdownSec = countdown,
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
                            onInterval = {
                                interval = it
                                it.toIntOrNull()?.let { n -> settings.scheduleIntervalSec = n }
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
                                val sec = interval.toIntOrNull() ?: 0
                                if (sec < Aprs.MIN_INTERVAL_SEC) {
                                    val msg = "Minimum interval is ${Aprs.MIN_INTERVAL_SEC} seconds"
                                    logs.add(msg, LogType.ERROR)
                                    BeaconRuntime.emitToast(msg, LogType.ERROR)
                                    return@MainScreen
                                }
                                settings.scheduleIntervalSec = sec
                                ensureRuntimePermissions()
                                if (!LocationHelper.hasFineLocation(this@MainActivity)) {
                                    BeaconRuntime.emitToast("Location permission required", LogType.ERROR)
                                    return@MainScreen
                                }
                                BeaconService.start(this@MainActivity)
                            },
                            onStopSchedule = { BeaconService.stop(this@MainActivity) },
                            onOpenLogs = { screen = "logs" },
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
