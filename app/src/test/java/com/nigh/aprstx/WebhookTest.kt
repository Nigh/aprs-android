package com.nigh.aprstx

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class WebhookTest {
    @Test fun validatesEndpointAndFixAge() {
        assertTrue(Webhook.validUrl("https://example.com:8443/hook?key=abc"))
        listOf("http://example.com", "https://", "https://user:pass@example.com", "https://example.com/#secret", "https://example.com:99999", "not a url").forEach {
            assertFalse(it, Webhook.validUrl(it))
        }
        val now = 100_000L
        val loc = AprsLocation(0.0, 0.0, timestampMs = now)
        assertTrue(Webhook.reliableLocation(loc, now))
        assertTrue(Webhook.reliableLocation(loc.copy(timestampMs = now - 60_000), now))
        assertFalse(Webhook.reliableLocation(loc.copy(timestampMs = now - 60_001), now))
        assertFalse(Webhook.reliableLocation(loc.copy(timestampMs = now + 1), now))
        assertFalse(Webhook.reliableLocation(loc.copy(latitude = Double.NaN), now))
        assertFalse(Webhook.reliableLocation(loc.copy(longitude = 181.0), now))
    }

    @Test fun payloadPreservesOptionalValuesAndNulls() {
        val hash = Webhook.deviceHash("device-one")
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(hash, Webhook.deviceHash("device-one"))
        assertNotEquals(hash, Webhook.deviceHash("device-two"))
        val loc = AprsLocation(22.5, 113.9, 5f, -12.5, 0f, 100_000L, 0f)
        val body = JSONObject(Webhook.payload("用户\"1", hash, loc))
        assertEquals("用户\"1", body.getString("id"))
        assertEquals(hash, body.getString("device_hash"))
        assertEquals(22.5, body.getDouble("latitude"), 0.0)
        assertEquals(113.9, body.getDouble("longitude"), 0.0)
        assertEquals(100_000L, body.getLong("timestamp_ms"))
        assertEquals(0.0, body.getDouble("speed_mps"), 0.0)
        assertEquals(0.0, body.getDouble("bearing_deg"), 0.0)
        assertEquals(-12.5, body.getDouble("altitude_m"), 0.0)
        assertEquals(5.0, body.getDouble("accuracy_m"), 0.0)
        val empty = JSONObject(Webhook.payload("id", hash, AprsLocation(1.0, 2.0)))
        listOf("accuracy_m", "speed_mps", "bearing_deg", "altitude_m").forEach {
            assertTrue(empty.has(it))
            assertTrue(empty.isNull(it))
        }
        val invalid = JSONObject(Webhook.payload("id", hash, loc.copy(speedMps = Float.NaN, bearingDeg = 360f)))
        assertTrue(invalid.isNull("speed_mps"))
        assertTrue(invalid.isNull("bearing_deg"))
    }

    @Test fun postUsesUtf8AndClosesWithoutFollowingRedirects() {
        val bytes = ByteArrayOutputStream()
        var disconnected = false
        val connection = object : HttpURLConnection(URL("https://example.com/hook")) {
            override fun connect() {}
            override fun disconnect() { disconnected = true }
            override fun usingProxy() = false
            override fun getOutputStream() = bytes
            override fun getResponseCode() = 302
        }
        val body = """{"id":"用户"}"""
        assertEquals(302, Webhook.post(connection, body))
        assertEquals(body, bytes.toString("UTF-8"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertTrue(disconnected)
    }
}
