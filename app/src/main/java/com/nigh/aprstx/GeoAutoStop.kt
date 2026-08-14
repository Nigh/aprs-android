package com.nigh.aprstx

data class StopZone(
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
    val enabled: Boolean = true,
) {
    companion object {
        const val MAX_ZONES = 16
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 1000
        const val DEFAULT_RADIUS_M = 100
        const val CLEAR_EXTRA_M = 50

        fun clampRadius(m: Int): Int = m.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    }
}

enum class GeoArm { UNKNOWN, NEED_CLEAR, ARMED }

data class GeoAutoStopResult(val arm: GeoArm, val stop: Boolean)

/** Pure geo auto-stop step (unit-tested). Only enabled zones count. */
fun geoAutoStopStep(
    latitude: Double,
    longitude: Double,
    zones: List<StopZone>,
    arm: GeoArm,
): GeoAutoStopResult {
    val active = zones.filter { it.enabled }
    if (active.isEmpty()) return GeoAutoStopResult(arm, stop = false)

    fun dist(z: StopZone) =
        Aprs.haversineMeters(latitude, longitude, z.latitude, z.longitude)

    fun insideAny() = active.any { dist(it) <= it.radiusM }

    fun clearedAll() = active.all { dist(it) > it.radiusM + StopZone.CLEAR_EXTRA_M }

    return when (arm) {
        GeoArm.UNKNOWN -> {
            if (insideAny()) GeoAutoStopResult(GeoArm.NEED_CLEAR, stop = false)
            else GeoAutoStopResult(GeoArm.ARMED, stop = false)
        }
        GeoArm.NEED_CLEAR -> {
            if (clearedAll()) GeoAutoStopResult(GeoArm.ARMED, stop = false)
            else GeoAutoStopResult(GeoArm.NEED_CLEAR, stop = false)
        }
        GeoArm.ARMED -> {
            if (insideAny()) GeoAutoStopResult(GeoArm.ARMED, stop = true)
            else GeoAutoStopResult(GeoArm.ARMED, stop = false)
        }
    }
}
