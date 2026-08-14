package com.nigh.aprstx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    var scheduleIntervalSec: Int
        get() = prefs.getInt("scheduleInterval", 60).coerceAtLeast(Aprs.MIN_INTERVAL_SEC)
        set(v) = prefs.edit().putInt("scheduleInterval", v.coerceAtLeast(Aprs.MIN_INTERVAL_SEC)).apply()

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
}
