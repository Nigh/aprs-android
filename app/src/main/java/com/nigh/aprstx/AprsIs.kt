package com.nigh.aprstx

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * APRS-IS Tier2 client (TCP 14580).
 * Picks a regional rotate hostname from GPS; falls back to worldwide rotate.
 */
object AprsIs {
    const val PORT = 14580
    const val SOFTWARE = "APRS-TX"
    const val VERSION = "1.0"
    /** Receive almost nothing — we only TX. */
    const val FILTER = "m/1"

    const val HOST_WORLD = "rotate.aprs2.net"
    const val HOST_NOAM = "noam.aprs2.net"
    const val HOST_SOAM = "soam.aprs2.net"
    const val HOST_EURO = "euro.aprs2.net"
    const val HOST_ASIA = "asia.aprs2.net"
    const val HOST_AUNZ = "aunz.aprs2.net"

    /**
     * Regional rotate from https://www.aprs2.net/ — rough boxes, good enough for DNS rotate.
     * Unknown / missing coords → worldwide rotate.
     */
    fun selectRotateHost(latitude: Double?, longitude: Double?): String {
        if (latitude == null || longitude == null) return HOST_WORLD
        val lat = latitude
        val lon = longitude
        // Oceania before Asia (Australia / NZ sit east of 110°E, south of equator)
        if (lat < 0.0 && lon > 110.0 && lon < 180.0) return HOST_AUNZ
        if (lat in 15.0..72.0 && lon in -170.0..-50.0) return HOST_NOAM
        if (lat in -56.0..15.0 && lon in -90.0..-34.0) return HOST_SOAM
        if (lat in -35.0..72.0 && lon in -25.0..60.0) return HOST_EURO
        if (lat in -10.0..70.0 && lon in 60.0..150.0) return HOST_ASIA
        return HOST_WORLD
    }

    fun loginLine(callsign: String, passcode: String): String {
        val call = callsign.trim().uppercase(Locale.US)
        val pass = passcode.trim()
        return "user $call pass $pass vers $SOFTWARE $VERSION filter $FILTER"
    }

    /**
     * Blocking: connect → login → send TNC2 packets → close.
     * Tries up to 3 DNS A records from the rotate hostname.
     */
    fun transmit(
        packets: List<String>,
        callsign: String,
        passcode: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): TransmitResult {
        if (packets.isEmpty()) {
            return TransmitResult(false, "No packets to transmit")
        }
        val host = selectRotateHost(latitude, longitude)
        val call = callsign.trim().uppercase(Locale.US)
        return try {
            val addrs = InetAddress.getAllByName(host).toList()
            if (addrs.isEmpty()) {
                return TransmitResult(false, "DNS failed for $host")
            }
            var lastError = "no address tried"
            for (addr in addrs.take(3)) {
                try {
                    transmitTo(addr, host, call, passcode, packets)
                    return TransmitResult(
                        true,
                        "APRS packet transmitted for $call via $host (${addr.hostAddress})",
                    )
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            TransmitResult(false, "Failed APRS-IS TX via $host: $lastError")
        } catch (e: Exception) {
            TransmitResult(false, "Failed APRS-IS TX via $host: ${e.message ?: "Unknown error"}")
        }
    }

    private fun transmitTo(
        addr: InetAddress,
        host: String,
        callsign: String,
        passcode: String,
        packets: List<String>,
    ) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(addr, PORT), 12_000)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

            // Server banner (# ...)
            val banner = readLineOrThrow(reader, "server banner")
            if (!banner.startsWith("#")) {
                throw Exception("Unexpected banner from $host: $banner")
            }

            writer.write(loginLine(callsign, passcode))
            writer.write("\r\n")
            writer.flush()

            val logresp = waitForLogresp(reader)
            val lower = logresp.lowercase(Locale.US)
            // "unverified" contains the letters of "verified" — check unverified first
            if ("unverified" in lower || !Regex("""\bverified\b""").containsMatchIn(lower)) {
                throw Exception("Login not verified: $logresp")
            }

            for (packet in packets) {
                writer.write(packet.trim())
                writer.write("\r\n")
            }
            writer.flush()
            // Brief drain so server can ack; ignore content
            socket.soTimeout = 1_500
            try {
                while (reader.readLine() != null) {
                    // discard
                }
            } catch (_: Exception) {
                // timeout expected
            }
        }
    }

    private fun readLineOrThrow(reader: BufferedReader, what: String): String {
        return reader.readLine() ?: throw Exception("EOF while reading $what")
    }

    private fun waitForLogresp(reader: BufferedReader): String {
        repeat(8) {
            val line = readLineOrThrow(reader, "login response")
            if (line.contains("logresp", ignoreCase = true)) return line
        }
        throw Exception("No logresp from server")
    }
}
