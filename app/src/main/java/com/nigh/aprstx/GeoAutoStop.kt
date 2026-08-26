package com.nigh.aprstx

import java.util.UUID

data class StopZone(
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
    val enabled: Boolean = true,
    val id: String = UUID.randomUUID().toString(),
    val note: String = "",
) {
    companion object {
        const val MAX_ZONES = 16
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 5000
        const val DEFAULT_RADIUS_M = 100
        const val MAX_NOTE_CODE_POINTS = 64
        const val CLEAR_EXTRA_M = 50
        const val LARGE_RADIUS_THRESHOLD_M = 1000
        const val LARGE_CLEAR_EXTRA_M = 100

        fun clampRadius(m: Int): Int = m.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        fun clampNote(note: String): String {
            val count = minOf(note.codePointCount(0, note.length), MAX_NOTE_CODE_POINTS)
            return note.substring(0, note.offsetByCodePoints(0, count))
        }
        fun clearExtraM(radiusM: Int): Int =
            if (radiusM > LARGE_RADIUS_THRESHOLD_M) LARGE_CLEAR_EXTRA_M else CLEAR_EXTRA_M
    }
}

enum class GeoArm { UNKNOWN, NEED_CLEAR, ARMED }
enum class GeoZoneEvent { NEED_CLEAR, ARMED, STOPPED }

data class GeoAutoStopResult(
    val arm: GeoArm,
    val stop: Boolean,
    val event: GeoZoneEvent? = null,
    val eventZoneIds: Set<String> = emptySet(),
)

/** Pure geo auto-stop step (unit-tested). Only enabled zones count. */
fun geoAutoStopStep(latitude: Double, longitude: Double, zones: List<StopZone>, arm: GeoArm): GeoAutoStopResult {
    val active = zones.filter { it.enabled }
    if (active.isEmpty()) return GeoAutoStopResult(arm, stop = false)
    fun dist(z: StopZone) = Aprs.haversineMeters(latitude, longitude, z.latitude, z.longitude)
    fun insideZones() = active.filter { dist(it) <= it.radiusM }
    fun clearedAll() = active.all { dist(it) > it.radiusM + StopZone.clearExtraM(it.radiusM) }
    return when (arm) {
        GeoArm.UNKNOWN -> {
            val inside = insideZones()
            if (inside.isNotEmpty()) GeoAutoStopResult(GeoArm.NEED_CLEAR, false, GeoZoneEvent.NEED_CLEAR, inside.map { it.id }.toSet())
            else GeoAutoStopResult(GeoArm.ARMED, false, GeoZoneEvent.ARMED, active.map { it.id }.toSet())
        }
        GeoArm.NEED_CLEAR -> if (clearedAll()) GeoAutoStopResult(GeoArm.ARMED, false, GeoZoneEvent.ARMED, active.map { it.id }.toSet())
        else GeoAutoStopResult(GeoArm.NEED_CLEAR, stop = false)
        GeoArm.ARMED -> {
            val inside = insideZones()
            if (inside.isNotEmpty()) GeoAutoStopResult(GeoArm.ARMED, true, GeoZoneEvent.STOPPED, inside.map { it.id }.toSet())
            else GeoAutoStopResult(GeoArm.ARMED, stop = false)
        }
    }
}
