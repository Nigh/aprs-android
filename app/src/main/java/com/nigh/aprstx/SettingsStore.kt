package com.nigh.aprstx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User settings for export/import (no lastTx / lastLocation). */
data class SettingsBackup(
    val callsign: String = "",
    val passcode: String = "",
    val commentText: String = "",
    val statusText: String = "",
    val minIntervalSec: Int = 60,
    val maxIntervalSec: Int = Aprs.DEFAULT_MAX_INTERVAL_SEC,
    val smartMoveEnabled: Boolean = false,
    val moveThresholdM: Int = Aprs.DEFAULT_MOVE_M,
    val autoStartOnWifiDisconnect: Boolean = false,
    val autoStopOnWifiConnect: Boolean = false,
    val stopZones: List<StopZone> = emptyList(),
)

fun encodeSettingsBackup(b: SettingsBackup): String {
    val zones = JSONArray()
    b.stopZones.take(StopZone.MAX_ZONES).forEach { z ->
        zones.put(
            JSONObject()
                .put("lat", z.latitude)
                .put("lon", z.longitude)
                .put("radiusM", StopZone.clampRadius(z.radiusM))
                .put("enabled", z.enabled),
        )
    }
    return JSONObject()
        .put("v", 1)
        .put("callsign", b.callsign)
        .put("passcode", b.passcode)
        .put("commentText", b.commentText)
        .put("statusText", b.statusText)
        .put("minIntervalSec", b.minIntervalSec)
        .put("maxIntervalSec", b.maxIntervalSec)
        .put("smartMoveEnabled", b.smartMoveEnabled)
        .put("moveThresholdM", b.moveThresholdM)
        .put("autoStartOnWifiDisconnect", b.autoStartOnWifiDisconnect)
        .put("autoStopOnWifiConnect", b.autoStopOnWifiConnect)
        .put("stopZones", zones)
        .toString()
}

fun decodeSettingsBackup(raw: String): SettingsBackup? = runCatching {
    val o = JSONObject(raw)
    val arr = o.optJSONArray("stopZones") ?: JSONArray()
    val zones = buildList {
        for (i in 0 until minOf(arr.length(), StopZone.MAX_ZONES)) {
            val z = arr.getJSONObject(i)
            add(
                StopZone(
                    latitude = z.getDouble("lat"),
                    longitude = z.getDouble("lon"),
                    radiusM = StopZone.clampRadius(z.optInt("radiusM", StopZone.DEFAULT_RADIUS_M)),
                    enabled = z.optBoolean("enabled", true),
                ),
            )
        }
    }
    SettingsBackup(
        callsign = o.optString("callsign", ""),
        passcode = o.optString("passcode", ""),
        commentText = o.optString("commentText", ""),
        statusText = o.optString("statusText", ""),
        minIntervalSec = o.optInt("minIntervalSec", 60),
        maxIntervalSec = o.optInt("maxIntervalSec", Aprs.DEFAULT_MAX_INTERVAL_SEC),
        smartMoveEnabled = o.optBoolean("smartMoveEnabled", false),
        moveThresholdM = o.optInt("moveThresholdM", Aprs.DEFAULT_MOVE_M),
        autoStartOnWifiDisconnect = o.optBoolean("autoStartOnWifiDisconnect", false),
        autoStopOnWifiConnect = o.optBoolean("autoStopOnWifiConnect", false),
        stopZones = zones,
    )
}.getOrNull()

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aprs-settings", Context.MODE_PRIVATE)

    var callsign: String
        get() = prefs.getString("callsign", "") ?: ""
        set(v) = prefs.edit().putString("callsign", v).apply()

    var passcode: String
        get() = prefs.getString("passcode", "") ?: ""
        set(v) = prefs.edit().putString("passcode", v).apply()

    var commentText: String
        get() = prefs.getString("commentText", "") ?: ""
        set(v) = prefs.edit().putString("commentText", v).apply()

    var statusText: String
        get() = prefs.getString("statusText", "") ?: ""
        set(v) = prefs.edit().putString("statusText", v).apply()

    var minIntervalSec: Int
        get() = Aprs.clampIntervalSec(
            prefs.getInt("minIntervalSec", prefs.getInt("scheduleInterval", 60)),
        )
        set(v) {
            val min = Aprs.clampIntervalSec(v)
            val e = prefs.edit().putInt("minIntervalSec", min)
            if (!smartMoveEnabled || prefs.getInt("maxIntervalSec", Aprs.DEFAULT_MAX_INTERVAL_SEC) < min) {
                e.putInt("maxIntervalSec", min)
            }
            e.apply()
        }

    var maxIntervalSec: Int
        get() {
            val min = minIntervalSec
            if (!smartMoveEnabled) return min
            return prefs.getInt("maxIntervalSec", Aprs.DEFAULT_MAX_INTERVAL_SEC)
                .coerceIn(min, Aprs.MAX_INTERVAL_SEC)
        }
        set(v) {
            val min = minIntervalSec
            prefs.edit().putInt("maxIntervalSec", v.coerceIn(min, Aprs.MAX_INTERVAL_SEC)).apply()
        }

    var smartMoveEnabled: Boolean
        get() = prefs.getBoolean("smartMoveEnabled", false)
        set(v) {
            val e = prefs.edit().putBoolean("smartMoveEnabled", v)
            if (!v) e.putInt("maxIntervalSec", minIntervalSec)
            e.apply()
        }

    var moveThresholdM: Int
        get() = Aprs.clampMoveM(prefs.getInt("moveThresholdM", Aprs.DEFAULT_MOVE_M))
        set(v) = prefs.edit().putInt("moveThresholdM", Aprs.clampMoveM(v)).apply()

    var lastTxAtMs: Long
        get() = prefs.getLong("lastTxAtMs", 0L)
        set(v) = prefs.edit().putLong("lastTxAtMs", v).apply()

    var lastTxLat: Double?
        get() = if (prefs.contains("lastTxLat")) Double.fromBits(prefs.getLong("lastTxLat", 0L)) else null
        set(v) {
            if (v == null) prefs.edit().remove("lastTxLat").apply()
            else prefs.edit().putLong("lastTxLat", v.toRawBits()).apply()
        }

    var lastTxLon: Double?
        get() = if (prefs.contains("lastTxLon")) Double.fromBits(prefs.getLong("lastTxLon", 0L)) else null
        set(v) {
            if (v == null) prefs.edit().remove("lastTxLon").apply()
            else prefs.edit().putLong("lastTxLon", v.toRawBits()).apply()
        }

    var autoStartOnWifiDisconnect: Boolean
        get() = prefs.getBoolean("autoStartOnWifiDisconnect", false)
        set(v) = prefs.edit().putBoolean("autoStartOnWifiDisconnect", v).apply()

    var autoStopOnWifiConnect: Boolean
        get() = prefs.getBoolean("autoStopOnWifiConnect", false)
        set(v) = prefs.edit().putBoolean("autoStopOnWifiConnect", v).apply()

    var stopZones: List<StopZone>
        get() {
            val raw = prefs.getString("stopZones", null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until minOf(arr.length(), StopZone.MAX_ZONES)) {
                        val o = arr.getJSONObject(i)
                        add(
                            StopZone(
                                latitude = o.getDouble("lat"),
                                longitude = o.getDouble("lon"),
                                radiusM = StopZone.clampRadius(o.optInt("radiusM", StopZone.DEFAULT_RADIUS_M)),
                                enabled = o.optBoolean("enabled", true),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        set(v) {
            val arr = JSONArray()
            v.take(StopZone.MAX_ZONES).forEach { z ->
                arr.put(
                    JSONObject()
                        .put("lat", z.latitude)
                        .put("lon", z.longitude)
                        .put("radiusM", StopZone.clampRadius(z.radiusM))
                        .put("enabled", z.enabled),
                )
            }
            prefs.edit().putString("stopZones", arr.toString()).apply()
        }

    var lastLocation: AprsLocation?
        get() {
            if (!prefs.contains("lat")) return null
            return AprsLocation(
                latitude = Double.fromBits(prefs.getLong("lat", 0L)),
                longitude = Double.fromBits(prefs.getLong("lon", 0L)),
                accuracy = if (prefs.contains("acc")) prefs.getFloat("acc", 0f) else null,
                speedMps = if (prefs.contains("spd")) prefs.getFloat("spd", 0f) else null,
                timestampMs = prefs.getLong("locTs", 0L),
            )
        }
        set(v) {
            if (v == null) {
                prefs.edit()
                    .remove("lat").remove("lon").remove("acc").remove("spd").remove("locTs")
                    .apply()
                return
            }
            val e = prefs.edit()
                .putLong("lat", v.latitude.toRawBits())
                .putLong("lon", v.longitude.toRawBits())
                .putLong("locTs", v.timestampMs)
            if (v.accuracy != null) e.putFloat("acc", v.accuracy) else e.remove("acc")
            if (v.speedMps != null) e.putFloat("spd", v.speedMps) else e.remove("spd")
            e.apply()
        }

    fun toBackup() = SettingsBackup(
        callsign = callsign,
        passcode = passcode,
        commentText = commentText,
        statusText = statusText,
        minIntervalSec = minIntervalSec,
        maxIntervalSec = maxIntervalSec,
        smartMoveEnabled = smartMoveEnabled,
        moveThresholdM = moveThresholdM,
        autoStartOnWifiDisconnect = autoStartOnWifiDisconnect,
        autoStopOnWifiConnect = autoStopOnWifiConnect,
        stopZones = stopZones,
    )

    fun applyBackup(b: SettingsBackup) {
        callsign = b.callsign
        passcode = b.passcode
        commentText = b.commentText
        statusText = b.statusText
        smartMoveEnabled = b.smartMoveEnabled
        minIntervalSec = b.minIntervalSec
        maxIntervalSec = b.maxIntervalSec
        moveThresholdM = b.moveThresholdM
        autoStartOnWifiDisconnect = b.autoStartOnWifiDisconnect
        autoStopOnWifiConnect = b.autoStopOnWifiConnect
        stopZones = b.stopZones
    }

    fun exportJson(): String = encodeSettingsBackup(toBackup())

    fun importJson(raw: String): Boolean {
        val b = decodeSettingsBackup(raw) ?: return false
        applyBackup(b)
        return true
    }
}
