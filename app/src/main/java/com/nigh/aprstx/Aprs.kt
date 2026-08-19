package com.nigh.aprstx

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class AprsLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val speedMps: Float? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class ValidationResult(val valid: Boolean, val message: String? = null)

data class TransmitResult(
    val success: Boolean,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

object Aprs {
    const val MIN_INTERVAL_SEC = 30
    const val MAX_INTERVAL_SEC = 3600
    const val DEFAULT_MAX_INTERVAL_SEC = 300
    const val MIN_MOVE_M = 100
    const val MAX_MOVE_M = 1000
    const val DEFAULT_MOVE_M = 100
    const val STALE_LOCATION_MS = 60_000L

    fun clampIntervalSec(v: Int): Int = v.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
    fun clampMoveM(v: Int): Int = v.coerceIn(MIN_MOVE_M, MAX_MOVE_M)

    fun formatLatitude(lat: Double): String {
        val absLat = kotlin.math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val dStr = degrees.toString().padStart(2, '0')
        val mStr = String.format(Locale.US, "%.2f", minutes).padStart(5, '0')
        val dir = if (lat >= 0) 'N' else 'S'
        return "$dStr$mStr$dir"
    }

    fun formatLongitude(lon: Double): String {
        val absLon = kotlin.math.abs(lon)
        val degrees = absLon.toInt()
        val minutes = (absLon - degrees) * 60.0
        val dStr = degrees.toString().padStart(3, '0')
        val mStr = String.format(Locale.US, "%.2f", minutes).padStart(5, '0')
        val dir = if (lon >= 0) 'E' else 'W'
        return "$dStr$mStr$dir"
    }

    fun formatSpeedKnots(speedMps: Float?): String? {
        if (speedMps == null || speedMps < 0f) return null
        // APRS CSE/SPD speed field is 3 digits (knots), clamp overflow
        val knots = kotlin.math.round(speedMps * 1.94384f).toInt().coerceIn(0, 999)
        return knots.toString().padStart(3, '0')
    }

    fun generatePackets(
        callsign: String,
        latitude: Double,
        longitude: Double,
        commentText: String? = null,
        statusText: String? = null,
        speedMps: Float? = null,
    ): List<String> {
        val clean = callsign.trim().uppercase(Locale.US)
        val lat = formatLatitude(latitude)
        val lon = formatLongitude(longitude)
        val head = "$clean>APRS,TCPIP*:"
        // Uncompressed position: !lat/lonSYMBOL[CSE/SPD]comment — symbol `[` then optional course/speed
        // ponytail: course hardcoded 000 (unknown); plumb GPS bearing when we care
        val speed = formatSpeedKnots(speedMps)
        val cseSpd = if (speed != null) "000/$speed" else ""
        val position = "$head!$lat/$lon[$cseSpd"
        val packets = mutableListOf(position + (commentText ?: ""))
        if (!statusText.isNullOrEmpty()) {
            packets.add("$head>$statusText")
        }
        return packets
    }

    fun validateCallsign(callsign: String, passcode: String): ValidationResult {
        val normalizedCallsign = callsign.trim().uppercase(Locale.US)
        val normalizedPasscode = passcode.trim()

        if (normalizedCallsign.isEmpty()) {
            return ValidationResult(false, "Please enter a CALLSIGN")
        }
        if (!Regex("^[A-Z0-9]{1,6}(-[0-9]{1,2})?$").matches(normalizedCallsign)) {
            return ValidationResult(false, "CALLSIGN format is invalid (example: N0CALL-1)")
        }
        val ssid = normalizedCallsign.substringAfter('-', "")
        if (ssid.isNotEmpty()) {
            val ssidValue = ssid.toIntOrNull()
            if (ssidValue == null || ssidValue !in 0..15) {
                return ValidationResult(false, "CALLSIGN SSID must be between 0 and 15")
            }
        }
        if (normalizedPasscode.isEmpty()) {
            return ValidationResult(false, "Please enter a PASSCODE")
        }
        if (!Regex("^-?\\d{1,5}$").matches(normalizedPasscode)) {
            return ValidationResult(false, "PASSCODE format is invalid")
        }
        return ValidationResult(true)
    }

    fun isLocationStale(location: AprsLocation?, maxAgeMs: Long = STALE_LOCATION_MS): Boolean {
        if (location == null) return true
        return System.currentTimeMillis() - location.timestampMs > maxAgeMs
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun averageSpeedMps(previous: AprsLocation, current: AprsLocation): Float? {
        val dtMs = current.timestampMs - previous.timestampMs
        if (dtMs > 300_000L || dtMs < 1_000L) return null
        val distance = haversineMeters(
            previous.latitude, previous.longitude,
            current.latitude, current.longitude,
        )
        val speed = (distance / (dtMs / 1000.0)).toFloat()
        if (speed > 200f) return null
        return speed
    }

    /** Blocking APRS-IS TCP TX — call off the main thread. */
    fun transmit(
        packets: List<String>,
        callsign: String,
        passcode: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): TransmitResult = AprsIs.transmit(packets, callsign, passcode, latitude, longitude)
}
