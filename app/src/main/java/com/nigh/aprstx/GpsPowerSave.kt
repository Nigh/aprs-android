package com.nigh.aprstx

/** Runtime GPS poll backoff when indoors (unit-tested). */
data class GpsPowerSaveState(
    val failStreak: Int = 0,
    val gpsIntervalSec: Int,
)

object GpsPowerSave {
    const val FAIL_THRESHOLD = 3
    const val STEP_SEC = 30
    const val MAX_INTERVAL_SEC = 300
    /** Beacon treats OS last-known older than this as timeout (not a fix). */
    const val MAX_FALLBACK_AGE_MS = 30_000L
}

/**
 * On success → interval back to [baseMinSec].
 * Every [failThreshold] consecutive failures → +[stepSec] (cap [maxIntervalSec]).
 * If [baseMinSec] already ≥ [maxIntervalSec], no intervention (stay at base).
 */
fun gpsPowerSaveStep(
    enabled: Boolean,
    gpsOk: Boolean,
    state: GpsPowerSaveState,
    baseMinSec: Int,
    failThreshold: Int = GpsPowerSave.FAIL_THRESHOLD,
    stepSec: Int = GpsPowerSave.STEP_SEC,
    maxIntervalSec: Int = GpsPowerSave.MAX_INTERVAL_SEC,
): GpsPowerSaveState {
    val base = Aprs.clampIntervalSec(baseMinSec)
    // Already at/above power-save ceiling — leave configured interval alone
    if (!enabled || base >= maxIntervalSec) return GpsPowerSaveState(0, base)
    if (gpsOk) return GpsPowerSaveState(0, base)
    val cur = state.gpsIntervalSec.coerceIn(base, maxIntervalSec)
    val streak = state.failStreak + 1
    if (streak < failThreshold) return GpsPowerSaveState(streak, cur)
    return GpsPowerSaveState(0, (cur + stepSec).coerceAtMost(maxIntervalSec))
}
