package com.nigh.aprstx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local beacon UI state (service writes, Activity reads). */
object BeaconRuntime {
    enum class ZoneVisualState { NEED_CLEAR, ARMED, STOPPED }
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _countdownSec = MutableStateFlow(0)
    val countdownSec: StateFlow<Int> = _countdownSec.asStateFlow()

    private val _intervalSec = MutableStateFlow(60)
    val intervalSec: StateFlow<Int> = _intervalSec.asStateFlow()

    private val _lastLocation = MutableStateFlow<AprsLocation?>(null)
    val lastLocation: StateFlow<AprsLocation?> = _lastLocation.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<Pair<String, LogType>?>(null)
    val toast: StateFlow<Pair<String, LogType>?> = _toast.asStateFlow()

    private val _zoneVisuals = MutableStateFlow<Map<String, ZoneVisualState>>(emptyMap())
    val zoneVisuals: StateFlow<Map<String, ZoneVisualState>> = _zoneVisuals.asStateFlow()
    private var resetZoneVisualsOnNextEvent = false

    private val _txTrack = MutableStateFlow<List<AprsLocation>>(emptyList())
    val txTrack: StateFlow<List<AprsLocation>> = _txTrack.asStateFlow()

    fun setActive(v: Boolean) {
        _active.value = v
    }

    fun setCountdown(v: Int) {
        _countdownSec.value = v
    }

    fun setInterval(v: Int) {
        _intervalSec.value = v
    }

    fun setLocation(v: AprsLocation?) {
        _lastLocation.value = v
    }

    fun setBusy(v: Boolean) {
        _busy.value = v
    }

    fun emitToast(message: String, type: LogType) {
        _toast.value = message to type
    }

    fun clearToast() {
        _toast.value = null
    }

    /** Preserve a stop highlight until the first geo decision of the next scheduled session. */
    fun beginGeoSession() {
        resetZoneVisualsOnNextEvent = true
        _txTrack.value = emptyList()
    }

    fun recordSuccessfulTx(location: AprsLocation) {
        _txTrack.value = _txTrack.value + location
    }

    fun recordGeoEvent(event: GeoZoneEvent?, zoneIds: Set<String>) {
        if (event == null) return
        val next = if (resetZoneVisualsOnNextEvent) emptyMap() else _zoneVisuals.value
        resetZoneVisualsOnNextEvent = false
        val state = when (event) {
            GeoZoneEvent.NEED_CLEAR -> ZoneVisualState.NEED_CLEAR
            GeoZoneEvent.ARMED -> ZoneVisualState.ARMED
            GeoZoneEvent.STOPPED -> ZoneVisualState.STOPPED
        }
        _zoneVisuals.value = next + zoneIds.associateWith { state }
    }
}
