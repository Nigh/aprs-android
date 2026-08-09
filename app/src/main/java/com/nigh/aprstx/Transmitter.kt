package com.nigh.aprstx

import android.content.Context

/** Shared GPS + APRS TX used by UI and BeaconService. */
object Transmitter {
    suspend fun ensureFreshLocation(context: Context, settings: SettingsStore): AprsLocation {
        val previous = settings.lastLocation ?: BeaconRuntime.lastLocation.value
        if (previous != null && !Aprs.isLocationStale(previous)) {
            BeaconRuntime.setLocation(previous)
            return previous
        }
        return LocationHelper.getLocation(context, previous).also {
            settings.lastLocation = it
            BeaconRuntime.setLocation(it)
        }
    }

    suspend fun transmitOnce(
        context: Context,
        settings: SettingsStore,
        logs: LogStore,
        reason: String,
    ): TransmitResult {
        BeaconRuntime.setBusy(true)
        try {
            logs.add("Acquiring GPS location for $reason", LogType.INFO)
            val loc = try {
                ensureFreshLocation(context, settings)
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
            return result
        } finally {
            BeaconRuntime.setBusy(false)
        }
    }
}
