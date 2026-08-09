package com.nigh.aprstx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local beacon UI state (service writes, Activity reads). */
object BeaconRuntime {
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
}
