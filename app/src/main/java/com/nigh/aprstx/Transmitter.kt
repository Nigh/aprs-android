package com.nigh.aprstx

import android.content.Context

/** Shared GPS + APRS TX used by UI and BeaconService. */
object Transmitter {
    suspend fun ensureFreshLocation(
        context: Context,
        settings: SettingsStore,
        maxAgeMs: Long = Aprs.STALE_LOCATION_MS,
        maxFallbackAgeMs: Long = Long.MAX_VALUE,
    ): AprsLocation {
        val previous = settings.lastLocation ?: BeaconRuntime.lastLocation.value
        if (previous != null && !Aprs.isLocationStale(previous, maxAgeMs)) {
            BeaconRuntime.setLocation(previous)
            return previous
        }
        return LocationHelper.getLocation(context, previous, maxFallbackAgeMs = maxFallbackAgeMs).also {
            settings.lastLocation = it
            BeaconRuntime.setLocation(it)
        }
    }

    suspend fun transmitOnce(
        context: Context,
        settings: SettingsStore,
        logs: LogStore,
        reason: String,
        location: AprsLocation? = null,
    ): TransmitResult {
        BeaconRuntime.setBusy(true)
        try {
            val wait = txCooldownRemainingSec(System.currentTimeMillis(), settings.lastTxAtMs)
            if (wait > 0) {
                val msg = "Minimum interval ${Aprs.MIN_INTERVAL_SEC}s — wait ${wait}s"
                logs.add(msg, LogType.WARNING)
                BeaconRuntime.emitToast(msg, LogType.WARNING)
                return TransmitResult(false, msg)
            }

            logs.add("Acquiring GPS location for $reason", LogType.INFO)
            val loc = try {
                location ?: ensureFreshLocation(context, settings)
            } catch (e: Exception) {
                val msg = "GPS acquisition failed: ${e.message ?: "Unknown error"}"
                logs.add(msg, LogType.ERROR)
                BeaconRuntime.emitToast(msg, LogType.ERROR)
                return TransmitResult(false, msg)
            }

            val packets = Aprs.generatePackets(
                callsign = settings.callsign,
                latitude = loc.latitude,
                longitude = loc.longitude,
                commentText = settings.commentText.ifBlank { null },
                statusText = settings.statusText.ifBlank { null },
                speedMps = loc.speedMps,
            )
            val host = AprsIs.selectRotateHost(loc.latitude, loc.longitude)
            logs.add("APRS-IS → $host:14580", LogType.INFO)
            val result = Aprs.transmit(
                packets,
                settings.callsign,
                settings.passcode,
                loc.latitude,
                loc.longitude,
            )
            val type = if (result.success) LogType.SUCCESS else LogType.ERROR
            logs.add(result.message, type)
            BeaconRuntime.emitToast(result.message, type)
            if (result.success) {
                settings.lastTxAtMs = System.currentTimeMillis()
                settings.lastTxLat = loc.latitude
                settings.lastTxLon = loc.longitude
                BeaconRuntime.recordSuccessfulTx(loc)
            }
            return result
        } finally {
            BeaconRuntime.setBusy(false)
        }
    }
}
