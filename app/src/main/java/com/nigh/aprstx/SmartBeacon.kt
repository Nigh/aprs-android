package com.nigh.aprstx

enum class BeaconTxReason { FIRST, COOLDOWN, FIXED_INTERVAL, MOVED, MAX_INTERVAL, SKIP }

data class BeaconTxDecision(val send: Boolean, val reason: BeaconTxReason)

fun txCooldownRemainingSec(nowMs: Long, lastTxAtMs: Long): Int =
    remainingUntilIntervalSec(nowMs, lastTxAtMs, Aprs.MIN_INTERVAL_SEC)

fun remainingUntilIntervalSec(nowMs: Long, lastTxAtMs: Long, intervalSec: Int): Int {
    if (lastTxAtMs <= 0L) return 0
    val elapsed = ((nowMs - lastTxAtMs) / 1000L).toInt()
    return (intervalSec.coerceAtLeast(Aprs.MIN_INTERVAL_SEC) - elapsed).coerceAtLeast(0)
}

/** Pure scheduled-TX decision (unit-tested). GPS is polled separately at min interval. */
fun shouldBeaconTx(
    nowMs: Long,
    lastTxAtMs: Long,
    lastTxLat: Double?,
    lastTxLon: Double?,
    lat: Double,
    lon: Double,
    minSec: Int,
    maxSec: Int,
    smartMove: Boolean,
    moveThresholdM: Int,
): BeaconTxDecision {
    val minMs = Aprs.clampIntervalSec(minSec) * 1000L
    val maxMs = maxSec.coerceIn(Aprs.clampIntervalSec(minSec), Aprs.MAX_INTERVAL_SEC) * 1000L
    if (lastTxAtMs <= 0L || lastTxLat == null || lastTxLon == null) {
        return BeaconTxDecision(true, BeaconTxReason.FIRST)
    }
    val since = nowMs - lastTxAtMs
    if (since < Aprs.MIN_INTERVAL_SEC * 1000L || since < minMs) {
        return BeaconTxDecision(false, BeaconTxReason.COOLDOWN)
    }
    if (!smartMove) {
        return BeaconTxDecision(true, BeaconTxReason.FIXED_INTERVAL)
    }
    val dist = Aprs.haversineMeters(lastTxLat, lastTxLon, lat, lon)
    if (dist >= moveThresholdM) {
        return BeaconTxDecision(true, BeaconTxReason.MOVED)
    }
    if (since >= maxMs) {
        return BeaconTxDecision(true, BeaconTxReason.MAX_INTERVAL)
    }
    return BeaconTxDecision(false, BeaconTxReason.SKIP)
}
