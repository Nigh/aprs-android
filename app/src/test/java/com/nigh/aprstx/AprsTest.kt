package com.nigh.aprstx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsTest {
    @Test
    fun formatsLatitudeLongitude() {
        assertEquals("4903.50N", Aprs.formatLatitude(49.058333))
        assertEquals("07201.75W", Aprs.formatLongitude(-72.029166))
    }

    @Test
    fun generatesPositionPacketWithComment() {
        val packets = Aprs.generatePackets(
            callsign = "n0call-1",
            latitude = 49.058333,
            longitude = -72.029166,
            commentText = "hello",
            statusText = "on air",
            speedMps = null,
        )
        assertEquals(2, packets.size)
        assertEquals("N0CALL-1>APRS,TCPIP*:!4903.50N/07201.75W[hello", packets[0])
        assertEquals("N0CALL-1>APRS,TCPIP*:>on air", packets[1])
    }

    @Test
    fun generatesPositionPacketWithCourseSpeedBeforeComment() {
        // APRS101: symbol then CSE/SPD then comment — not /SPD[comment
        val packets = Aprs.generatePackets(
            callsign = "n0call-1",
            latitude = 49.058333,
            longitude = -72.029166,
            commentText = "hello",
            speedMps = 0.5144f, // ~1 kn
        )
        assertEquals(1, packets.size)
        assertEquals("N0CALL-1>APRS,TCPIP*:!4903.50N/07201.75W[000/001hello", packets[0])
    }

    @Test
    fun validatesCallsignAndPasscode() {
        assertTrue(Aprs.validateCallsign("N0CALL-1", "12345").valid)
        assertFalse(Aprs.validateCallsign("TOOLONGCALL", "1").valid)
        assertFalse(Aprs.validateCallsign("N0CALL-99", "1").valid)
        assertFalse(Aprs.validateCallsign("N0CALL-1", "").valid)
    }

    @Test
    fun formatsSpeedKnots() {
        assertEquals("019", Aprs.formatSpeedKnots(10f)) // ~19.4 kn → 19
        assertEquals(null, Aprs.formatSpeedKnots(null))
    }

    @Test
    fun selectsRegionalRotateHost() {
        assertEquals(AprsIs.HOST_WORLD, AprsIs.selectRotateHost(null, null))
        assertEquals(AprsIs.HOST_NOAM, AprsIs.selectRotateHost(40.7, -74.0)) // NYC
        assertEquals(AprsIs.HOST_SOAM, AprsIs.selectRotateHost(-23.5, -46.6)) // São Paulo
        assertEquals(AprsIs.HOST_EURO, AprsIs.selectRotateHost(51.5, -0.1)) // London
        assertEquals(AprsIs.HOST_ASIA, AprsIs.selectRotateHost(31.2, 121.5)) // Shanghai
        assertEquals(AprsIs.HOST_AUNZ, AprsIs.selectRotateHost(-33.9, 151.2)) // Sydney
        assertEquals(
            "user N0CALL-1 pass 12345 vers APRS-TX 1.0 filter m/1",
            AprsIs.loginLine("n0call-1", "12345"),
        )
    }
}
