package com.nigh.aprstx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground beacon loop: sleep between TX, single-shot GPS per cycle.
 * No continuous location / screen wake — only a short PARTIAL wake around each TX.
 */
class BeaconService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBeacon()
                return START_NOT_STICKY
            }
            else -> startBeacon()
        }
        return START_STICKY
    }

    private fun startBeacon() {
        if (loopJob?.isActive == true) return

        val settings = AppGraph.settings
        val logs = AppGraph.logs
        val interval = settings.minIntervalSec
        BeaconRuntime.setInterval(interval)
        BeaconRuntime.setActive(true)
        BeaconRuntime.setCountdown(interval)
        BeaconRuntime.beginGeoSession()

        val notif = buildNotification(interval, interval, settings)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )

        val mode = if (settings.smartMoveEnabled) {
            "GPS every ${interval}s, TX on ≥${settings.moveThresholdM}m or every ${settings.maxIntervalSec}s"
        } else {
            "TX every ${interval}s"
        }
        logs.add(
            "Scheduled transmissions started for ${settings.callsign}. $mode.",
            LogType.SUCCESS,
        )
        BeaconRuntime.emitToast(
            "Scheduled transmissions started for ${settings.callsign}. $mode.",
            LogType.SUCCESS,
        )

        loopJob = scope.launch {
            var geoArm = GeoArm.UNKNOWN
            var powerSave = GpsPowerSaveState(0, AppGraph.settings.minIntervalSec)
            var first = true
            while (isActive) {
                val settings = AppGraph.settings
                val minSec = settings.minIntervalSec
                val powerSaveActive =
                    settings.autoPowerSaveEnabled && minSec < GpsPowerSave.MAX_INTERVAL_SEC
                if (!powerSaveActive) {
                    powerSave = GpsPowerSaveState(0, minSec)
                }
                val pollSec = if (powerSaveActive) {
                    powerSave.gpsIntervalSec.coerceAtLeast(minSec)
                } else {
                    minSec
                }
                val waitSec = if (first) {
                    first = false
                    remainingUntilIntervalSec(
                        System.currentTimeMillis(),
                        settings.lastTxAtMs,
                        pollSec,
                    )
                } else {
                    pollSec
                }
                BeaconRuntime.setInterval(if (waitSec > 0) waitSec else pollSec)
                var remaining = waitSec
                BeaconRuntime.setCountdown(remaining)
                updateNotification(pollSec, remaining)
                while (isActive && remaining > 0) {
                    delay(1000)
                    remaining--
                    BeaconRuntime.setCountdown(remaining)
                    if (remaining % 5 == 0 || remaining <= 5) {
                        updateNotification(pollSec, remaining)
                    }
                }
                if (!isActive) break

                var stoppedByGeo = false
                withBriefWake {
                    val settings = AppGraph.settings
                    val loc = try {
                        Transmitter.ensureFreshLocation(
                            this@BeaconService,
                            settings,
                            maxAgeMs = 0L,
                            maxFallbackAgeMs = if (powerSaveActive) {
                                GpsPowerSave.MAX_FALLBACK_AGE_MS
                            } else {
                                Long.MAX_VALUE
                            },
                        )
                    } catch (_: Exception) {
                        null
                    }
                    val prevInterval = powerSave.gpsIntervalSec
                    powerSave = gpsPowerSaveStep(
                        enabled = powerSaveActive,
                        gpsOk = loc != null,
                        state = powerSave,
                        baseMinSec = settings.minIntervalSec,
                    )
                    if (powerSaveActive) {
                        when {
                            powerSave.gpsIntervalSec > prevInterval -> {
                                AppGraph.logs.add(
                                    "Auto power-save: GPS interval → ${powerSave.gpsIntervalSec}s",
                                    LogType.INFO,
                                )
                            }
                            loc != null && prevInterval > settings.minIntervalSec -> {
                                AppGraph.logs.add(
                                    "Auto power-save: GPS ok — interval restored to ${settings.minIntervalSec}s",
                                    LogType.INFO,
                                )
                            }
                        }
                    }
                    if (loc != null) {
                        val step = geoAutoStopStep(
                            loc.latitude,
                            loc.longitude,
                            settings.stopZones,
                            geoArm,
                        )
                        geoArm = step.arm
                        BeaconRuntime.recordGeoEvent(step.event, step.eventZoneIds)
                        if (step.stop) {
                            AppGraph.logs.add(
                                "Entered stop zone — auto-stopping schedule",
                                LogType.INFO,
                            )
                            BeaconRuntime.emitToast(
                                "Entered stop zone — auto-stopping schedule",
                                LogType.INFO,
                            )
                            stoppedByGeo = true
                            return@withBriefWake
                        }
                        val decision = shouldBeaconTx(
                            nowMs = System.currentTimeMillis(),
                            lastTxAtMs = settings.lastTxAtMs,
                            lastTxLat = settings.lastTxLat,
                            lastTxLon = settings.lastTxLon,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            minSec = settings.minIntervalSec,
                            maxSec = settings.maxIntervalSec,
                            smartMove = settings.smartMoveEnabled,
                            moveThresholdM = settings.moveThresholdM,
                        )
                        if (!decision.send) {
                            return@withBriefWake
                        }
                        Transmitter.transmitOnce(
                            this@BeaconService,
                            settings,
                            AppGraph.logs,
                            "scheduled transmission",
                            location = loc,
                        )
                    } else {
                        Transmitter.transmitOnce(
                            this@BeaconService,
                            AppGraph.settings,
                            AppGraph.logs,
                            "scheduled transmission",
                        )
                    }
                }
                if (stoppedByGeo) {
                    stopBeacon()
                    return@launch
                }
            }
        }
    }

    private fun stopBeacon() {
        loopJob?.cancel()
        loopJob = null
        BeaconRuntime.setActive(false)
        BeaconRuntime.setCountdown(0)
        val callsign = AppGraph.settings.callsign
        AppGraph.logs.add("Scheduled transmissions stopped for $callsign", LogType.SUCCESS)
        BeaconRuntime.emitToast(
            "Scheduled transmissions stopped for $callsign",
            LogType.SUCCESS,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        BeaconRuntime.setActive(false)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun withBriefWake(block: suspend () -> Unit) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aprstx:tx").apply {
            setReferenceCounted(false)
            // Ceiling: 60s — enough for one GPS fix + HTTP; upgrade: WorkManager if interval ≥15min
            acquire(60_000L)
        }
        try {
            block()
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.beacon_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.beacon_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(intervalSec: Int, remainingSec: Int, settings: SettingsStore = AppGraph.settings): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.beacon_notif_title))
            .setContentText(
                if (settings.smartMoveEnabled) {
                    "Next GPS in ${remainingSec}s (TX ${intervalSec}–${settings.maxIntervalSec}s)"
                } else {
                    "Next TX in ${remainingSec}s (every ${intervalSec}s)"
                },
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(intervalSec: Int, remainingSec: Int) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(intervalSec, remainingSec))
    }

    companion object {
        const val ACTION_STOP = "com.nigh.aprstx.STOP_BEACON"
        private const val CHANNEL_ID = "aprs_beacon"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, BeaconService::class.java)
            ContextCompatStartForeground(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BeaconService::class.java).setAction(ACTION_STOP))
        }

        private fun ContextCompatStartForeground(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
