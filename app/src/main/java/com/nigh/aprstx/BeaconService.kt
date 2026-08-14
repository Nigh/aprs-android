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
        val interval = settings.scheduleIntervalSec.coerceAtLeast(Aprs.MIN_INTERVAL_SEC)
        BeaconRuntime.setInterval(interval)
        BeaconRuntime.setActive(true)
        BeaconRuntime.setCountdown(interval)

        val notif = buildNotification(interval, interval)
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

        logs.add(
            "Scheduled transmissions started for ${settings.callsign}. Sending every ${interval}s.",
            LogType.SUCCESS,
        )
        BeaconRuntime.emitToast(
            "Scheduled transmissions started for ${settings.callsign}. Sending every ${interval}s.",
            LogType.SUCCESS,
        )

        loopJob = scope.launch {
            var lastTx = 0L
            var geoArm = GeoArm.UNKNOWN
            while (isActive) {
                val intervalSec = AppGraph.settings.scheduleIntervalSec.coerceAtLeast(Aprs.MIN_INTERVAL_SEC)
                BeaconRuntime.setInterval(intervalSec)
                val intervalMs = intervalSec * 1000L

                val since = System.currentTimeMillis() - lastTx
                if (lastTx == 0L || since >= (intervalMs * 0.95).toLong()) {
                    var stoppedByGeo = false
                    withBriefWake {
                        val loc = try {
                            Transmitter.ensureFreshLocation(this@BeaconService, AppGraph.settings)
                        } catch (_: Exception) {
                            null
                        }
                        if (loc != null) {
                            val step = geoAutoStopStep(
                                loc.latitude,
                                loc.longitude,
                                AppGraph.settings.stopZones,
                                geoArm,
                            )
                            geoArm = step.arm
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
                        }
                        Transmitter.transmitOnce(
                            this@BeaconService,
                            AppGraph.settings,
                            AppGraph.logs,
                            "scheduled transmission",
                        )
                    }
                    if (stoppedByGeo) {
                        stopBeacon()
                        return@launch
                    }
                    lastTx = System.currentTimeMillis()
                }

                // Tick countdown once per second without holding a wake lock.
                var remaining = intervalSec
                while (isActive && remaining > 0) {
                    delay(1000)
                    remaining--
                    BeaconRuntime.setCountdown(remaining)
                    if (remaining % 5 == 0 || remaining <= 5) {
                        updateNotification(intervalSec, remaining)
                    }
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

    private fun buildNotification(intervalSec: Int, remainingSec: Int): Notification {
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
            .setContentText("Next TX in ${remainingSec}s (every ${intervalSec}s)")
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
