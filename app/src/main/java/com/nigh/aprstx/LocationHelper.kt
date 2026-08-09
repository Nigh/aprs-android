package com.nigh.aprstx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object LocationHelper {
    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Single-shot fix via platform LocationManager (no Play Services).
     * Prefers a fresh last-known fix (&lt;30s) to avoid waking GPS when possible;
     * otherwise one update then removes the listener.
     */
    suspend fun getLocation(context: Context, previous: AprsLocation?, timeoutMs: Long = 20_000L): AprsLocation {
        if (!hasFineLocation(context)) {
            throw SecurityException("Location permission not granted")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val recent = bestLastKnown(lm)
        if (recent != null && ageMs(recent) < 30_000L) {
            return toAprs(recent, previous)
        }

        return suspendCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            var finished = false
            fun finish(block: () -> Unit) {
                if (finished) return
                finished = true
                block()
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    finish {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {
                        }
                        main.removeCallbacksAndMessages(null)
                        cont.resume(toAprs(location, previous))
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            val timeout = Runnable {
                finish {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                    val fallback = bestLastKnown(lm)
                    if (fallback != null) {
                        cont.resume(toAprs(fallback, previous))
                    } else {
                        cont.resumeWithException(Exception("GPS location timeout"))
                    }
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // ponytail: CancellationSignal omitted — timeout path removes updates / resumes once
                    val executor = ContextCompat.getMainExecutor(context)
                    val consumer = java.util.function.Consumer<Location?> { loc ->
                        finish {
                            main.removeCallbacks(timeout)
                            if (loc != null) {
                                cont.resume(toAprs(loc, previous))
                            } else {
                                val fallback = bestLastKnown(lm)
                                if (fallback != null) cont.resume(toAprs(fallback, previous))
                                else cont.resumeWithException(Exception("GPS location unavailable"))
                            }
                        }
                    }
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> LocationManager.PASSIVE_PROVIDER
                    }
                    lm.getCurrentLocation(provider, null, executor, consumer)
                } else {
                    val criteria = Criteria().apply {
                        accuracy = Criteria.ACCURACY_FINE
                        powerRequirement = Criteria.POWER_LOW
                    }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(criteria, listener, Looper.getMainLooper())
                }
                main.postDelayed(timeout, timeoutMs)
            } catch (e: Exception) {
                finish {
                    main.removeCallbacks(timeout)
                    cont.resumeWithException(e)
                }
            }
        }
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull { p ->
            try {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            } catch (_: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
    }

    private fun ageMs(location: Location): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
    }

    private fun toAprs(location: Location, previous: AprsLocation?): AprsLocation {
        var speed = if (location.hasSpeed()) location.speed else null
        val current = AprsLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speedMps = speed,
            timestampMs = System.currentTimeMillis(),
        )
        if (speed == null && previous != null) {
            speed = Aprs.averageSpeedMps(previous, current)
        }
        return current.copy(speedMps = speed)
    }
}
