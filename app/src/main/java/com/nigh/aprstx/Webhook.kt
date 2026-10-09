package com.nigh.aprstx

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

object Webhook {
    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)

    fun reliableLocation(loc: AprsLocation, nowMs: Long): Boolean =
        loc.latitude.isFinite() && loc.latitude in -90.0..90.0 &&
            loc.longitude.isFinite() && loc.longitude in -180.0..180.0 &&
            loc.timestampMs > 0 && loc.timestampMs <= nowMs &&
            nowMs - loc.timestampMs <= Aprs.STALE_LOCATION_MS

    fun deviceHash(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun deviceHash(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val identity = androidId?.takeIf { it.isNotBlank() } ?: run {
            val prefs = context.getSharedPreferences("webhook-device", Context.MODE_PRIVATE)
            prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("id", it).apply()
            }
        }
        return deviceHash("${context.packageName}|$identity|${Build.MANUFACTURER}|${Build.MODEL}")
    }

    fun payload(id: String, hash: String, loc: AprsLocation): String = JSONObject()
        .put("id", id)
        .put("device_hash", hash)
        .put("timestamp_ms", loc.timestampMs)
        .put("latitude", loc.latitude)
        .put("longitude", loc.longitude)
        .put("accuracy_m", loc.accuracy?.takeIf { it.isFinite() && it >= 0 } ?: JSONObject.NULL)
        .put("speed_mps", loc.speedMps?.takeIf { it.isFinite() && it >= 0 } ?: JSONObject.NULL)
        .put("bearing_deg", loc.bearingDeg?.takeIf { it.isFinite() && it >= 0 && it < 360 } ?: JSONObject.NULL)
        .put("altitude_m", loc.altitude?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .toString()

    internal fun post(connection: HttpURLConnection, body: String): Int {
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    suspend fun report(context: Context, settings: SettingsStore, logs: LogStore, loc: AprsLocation) {
        if (!settings.webhookEnabled) return
        if (!reliableLocation(loc, System.currentTimeMillis())) {
            logs.add("Webhook skipped: no reliable location (maximum age 60s)", LogType.WARNING)
            return
        }
        val url = settings.webhookUrl.trim()
        val id = settings.webhookId.trim()
        if (!validUrl(url) || id.isBlank()) {
            logs.add("Webhook skipped: HTTPS URL and reporting ID required", LogType.WARNING)
            return
        }
        try {
            val code = withContext(Dispatchers.IO) {
                post(URI(url).toURL().openConnection() as HttpURLConnection, payload(id, deviceHash(context), loc))
            }
            logs.add("Webhook HTTP $code", if (code in 200..299) LogType.SUCCESS else LogType.WARNING)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // URLs may contain credentials; do not put exception messages in persisted logs.
            logs.add("Webhook failed: network or TLS error", LogType.WARNING)
        }
    }
}
