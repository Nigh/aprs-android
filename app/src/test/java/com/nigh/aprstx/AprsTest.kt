package com.nigh.aprstx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsTest {
    @Test
    fun mapLibreBearingUsesOppositeRotationConvention() {
        assertEquals(-45.0, mapLibreBearing(45f), 0.0)
        assertEquals(90.0, mapLibreBearing(-90f), 0.0)
    }

    @Test
    fun scheduledSessionClearsSuccessfulTxTrack() {
        BeaconRuntime.beginGeoSession()
        val first = AprsLocation(1.0, 2.0)
        val second = AprsLocation(3.0, 4.0)
        BeaconRuntime.recordSuccessfulTx(first)
        BeaconRuntime.recordSuccessfulTx(second)
        assertEquals(listOf(first, second), BeaconRuntime.txTrack.value)
        BeaconRuntime.beginGeoSession()
        assertTrue(BeaconRuntime.txTrack.value.isEmpty())
    }

    @Test
    fun stopZoneNotesAndIdsRoundTrip() {
        val longNote = "😀".repeat(70)
        val zone = StopZone(22.5, 114.0, note = longNote)
        assertEquals(64, StopZone.clampNote(longNote).codePointCount(0, StopZone.clampNote(longNote).length))
        val decoded = decodeSettingsBackup(encodeSettingsBackup(SettingsBackup(stopZones = listOf(zone))))!!
        assertEquals(zone.id, decoded.stopZones.single().id)
        assertEquals(StopZone.clampNote(longNote), decoded.stopZones.single().note)
        val legacy = decodeSettingsBackup("""{"stopZones":[{"lat":1,"lon":2}]}""")!!
        assertTrue(legacy.stopZones.single().id.isNotBlank())
        assertEquals("", legacy.stopZones.single().note)
    }

    @Test
    fun geoAutoStopReportsVisualEvents() {
        val zone = StopZone(0.0, 0.0, radiusM = 100)
        val pending = geoAutoStopStep(0.0, 0.0, listOf(zone), GeoArm.UNKNOWN)
        assertEquals(GeoZoneEvent.NEED_CLEAR, pending.event)
        assertTrue(pending.txBlocked)
        assertEquals(setOf(zone.id), pending.eventZoneIds)
        val armed = geoAutoStopStep(0.002, 0.0, listOf(zone), GeoArm.UNKNOWN)
        assertEquals(GeoZoneEvent.ARMED, armed.event)
        val stopped = geoAutoStopStep(0.0, 0.0, listOf(zone), GeoArm.ARMED)
        assertEquals(GeoZoneEvent.STOPPED, stopped.event)
        assertTrue(stopped.txBlocked)
    }

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

    @Test
    fun wifiAutoActions() {
        assertEquals(
            WifiAutoAction.SCHEDULE_START,
            wifiAutoAction(wifiGained = false, autoStart = true, autoStop = false, beaconActive = false),
        )
        assertEquals(
            WifiAutoAction.NONE,
            wifiAutoAction(wifiGained = false, autoStart = true, autoStop = false, beaconActive = true),
        )
        assertEquals(
            WifiAutoAction.STOP,
            wifiAutoAction(wifiGained = true, autoStart = false, autoStop = true, beaconActive = true),
        )
        assertEquals(
            WifiAutoAction.CANCEL_PENDING,
            wifiAutoAction(wifiGained = true, autoStart = true, autoStop = true, beaconActive = false),
        )
        assertEquals(
            WifiAutoAction.NONE,
            wifiAutoAction(wifiGained = false, autoStart = false, autoStop = true, beaconActive = false),
        )
    }

    @Test
    fun wifiStopNeedsContinuousDisconnectBeforeArm() {
        val firstConnect = wifiStopStep(
            WifiStopArm.NEED_DISCONNECT,
            WifiStopEvent.CONNECTED,
            autoStop = true,
            beaconActive = true,
        )
        assertEquals(WifiStopArm.NEED_DISCONNECT, firstConnect.arm)
        assertFalse(firstConnect.stop)

        val disconnected = wifiStopStep(
            firstConnect.arm,
            WifiStopEvent.DISCONNECTED,
            autoStop = true,
            beaconActive = true,
        )
        assertEquals(WifiStopArm.ARM_PENDING, disconnected.arm)

        val reconnectedEarly = wifiStopStep(
            disconnected.arm,
            WifiStopEvent.CONNECTED,
            autoStop = true,
            beaconActive = true,
        )
        assertEquals(WifiStopArm.NEED_DISCONNECT, reconnectedEarly.arm)
        assertFalse(reconnectedEarly.stop)

        val restarted = wifiStopStep(
            reconnectedEarly.arm,
            WifiStopEvent.DISCONNECTED,
            autoStop = true,
            beaconActive = true,
        )
        val armed = wifiStopStep(
            restarted.arm,
            WifiStopEvent.ARM_TIMEOUT,
            autoStop = true,
            beaconActive = true,
        )
        assertEquals(WifiStopArm.ARMED, armed.arm)

        val connectedAfterArm = wifiStopStep(
            armed.arm,
            WifiStopEvent.CONNECTED,
            autoStop = true,
            beaconActive = true,
        )
        assertTrue(connectedAfterArm.stop)
    }

    @Test
    fun geoAutoStopArmAndEnter() {
        val zone = StopZone(0.0, 0.0, radiusM = 100, enabled = true)
        // ~200m north of origin (1° lat ≈ 111km)
        val outside = 200.0 / 111_000.0
        val far = 200.0 / 111_000.0 // also > radius+50

        val firstOutside = geoAutoStopStep(outside, 0.0, listOf(zone), GeoArm.UNKNOWN)
        assertEquals(GeoArm.ARMED, firstOutside.arm)
        assertFalse(firstOutside.stop)

        val enter = geoAutoStopStep(0.0, 0.0, listOf(zone), GeoArm.ARMED)
        assertEquals(GeoArm.ARMED, enter.arm)
        assertTrue(enter.stop)

        // stay outside while armed → no stop
        val stay = geoAutoStopStep(far, 0.0, listOf(zone), GeoArm.ARMED)
        assertEquals(GeoArm.ARMED, stay.arm)
        assertFalse(stay.stop)
    }

    @Test
    fun geoAutoStopNeedClearBeforeArm() {
        val zone = StopZone(0.0, 0.0, radiusM = 100, enabled = true)
        // just outside radius but inside radius+50 (~120m)
        val nearClear = 120.0 / 111_000.0
        val cleared = 160.0 / 111_000.0 // > 150m

        val startInside = geoAutoStopStep(0.0, 0.0, listOf(zone), GeoArm.UNKNOWN)
        assertEquals(GeoArm.NEED_CLEAR, startInside.arm)
        assertFalse(startInside.stop)
        assertTrue(startInside.txBlocked)

        val stillNear = geoAutoStopStep(nearClear, 0.0, listOf(zone), GeoArm.NEED_CLEAR)
        assertEquals(GeoArm.NEED_CLEAR, stillNear.arm)
        assertFalse(stillNear.stop)
        assertFalse(stillNear.txBlocked)

        val armed = geoAutoStopStep(cleared, 0.0, listOf(zone), GeoArm.NEED_CLEAR)
        assertEquals(GeoArm.ARMED, armed.arm)
        assertFalse(armed.stop)

        val reenter = geoAutoStopStep(0.0, 0.0, listOf(zone), GeoArm.ARMED)
        assertTrue(reenter.stop)
    }

    @Test
    fun geoAutoStopUsesLargerClearanceAboveOneKilometer() {
        assertEquals(5000, StopZone.clampRadius(6000))
        assertEquals(50, StopZone.clearExtraM(1000))
        assertEquals(100, StopZone.clearExtraM(1001))

        val zone = StopZone(0.0, 0.0, radiusM = 1500, enabled = true)
        val insideLargeClearance = 1575.0 / 111_000.0
        val beyondLargeClearance = 1610.0 / 111_000.0

        val stillNear = geoAutoStopStep(
            insideLargeClearance,
            0.0,
            listOf(zone),
            GeoArm.NEED_CLEAR,
        )
        assertEquals(GeoArm.NEED_CLEAR, stillNear.arm)

        val armed = geoAutoStopStep(
            beyondLargeClearance,
            0.0,
            listOf(zone),
            GeoArm.NEED_CLEAR,
        )
        assertEquals(GeoArm.ARMED, armed.arm)
    }

    @Test
    fun geoAutoStopIgnoresDisabledAndEmpty() {
        val disabled = StopZone(0.0, 0.0, radiusM = 100, enabled = false)
        val atCenter = geoAutoStopStep(0.0, 0.0, listOf(disabled), GeoArm.ARMED)
        assertEquals(GeoArm.ARMED, atCenter.arm)
        assertFalse(atCenter.stop)

        val empty = geoAutoStopStep(0.0, 0.0, emptyList(), GeoArm.NEED_CLEAR)
        assertEquals(GeoArm.NEED_CLEAR, empty.arm)
        assertFalse(empty.stop)
    }

    @Test
    fun containingStopZonesOnlyReturnsEnabledZonesContainingLocation() {
        val inside = StopZone(0.0, 0.0, radiusM = 100, note = "Home")
        val disabled = StopZone(0.0, 0.0, radiusM = 100, enabled = false)
        val outside = StopZone(1.0, 1.0, radiusM = 100)
        assertEquals(listOf(inside), containingStopZones(0.0, 0.0, listOf(inside, disabled, outside)))
    }

    @Test
    fun txCooldownAndBeaconDecision() {
        val t0 = 1_000_000L
        assertEquals(0, txCooldownRemainingSec(t0, 0L))
        assertEquals(20, txCooldownRemainingSec(t0, t0 - 10_000L))
        assertEquals(0, txCooldownRemainingSec(t0, t0 - 30_000L))
        assertEquals(50, remainingUntilIntervalSec(t0, t0 - 10_000L, 60))

        val first = shouldBeaconTx(t0, 0L, null, null, 0.0, 0.0, 30, 30, false, 200)
        assertTrue(first.send)
        assertEquals(BeaconTxReason.FIRST, first.reason)

        val cool = shouldBeaconTx(t0, t0 - 10_000L, 0.0, 0.0, 0.0, 0.0, 30, 30, false, 200)
        assertFalse(cool.send)
        assertEquals(BeaconTxReason.COOLDOWN, cool.reason)

        val fixed = shouldBeaconTx(t0, t0 - 30_000L, 0.0, 0.0, 0.0, 0.0, 30, 30, false, 200)
        assertTrue(fixed.send)
        assertEquals(BeaconTxReason.FIXED_INTERVAL, fixed.reason)

        val movedLat = 200.0 / 111_000.0
        val moved = shouldBeaconTx(t0, t0 - 30_000L, 0.0, 0.0, movedLat, 0.0, 30, 600, true, 100)
        assertTrue(moved.send)
        assertEquals(BeaconTxReason.MOVED, moved.reason)

        val skip = shouldBeaconTx(t0, t0 - 30_000L, 0.0, 0.0, 0.0, 0.0, 30, 600, true, 100)
        assertFalse(skip.send)
        assertEquals(BeaconTxReason.SKIP, skip.reason)

        val forced = shouldBeaconTx(t0, t0 - 600_000L, 0.0, 0.0, 0.0, 0.0, 30, 600, true, 100)
        assertTrue(forced.send)
        assertEquals(BeaconTxReason.MAX_INTERVAL, forced.reason)
    }

    @Test
    fun settingsBackupRoundTrip() {
        val original = SettingsBackup(
            callsign = "BA7NTM-7",
            passcode = "12345",
            commentText = "hello",
            statusText = "on air",
            minIntervalSec = 90,
            maxIntervalSec = 600,
            smartMoveEnabled = true,
            moveThresholdM = 200,
            autoStartOnWifiDisconnect = true,
            autoStopOnWifiConnect = false,
            autoPowerSaveEnabled = false,
            stopZones = listOf(
                StopZone(22.5, 114.0, 150, enabled = true),
                StopZone(-1.0, 2.0, 50, enabled = false),
            ),
        )
        val decoded = decodeSettingsBackup(encodeSettingsBackup(original))
        assertEquals(original, decoded)
        assertEquals(null, decodeSettingsBackup("not-json"))
        // legacy JSON without autoPowerSaveEnabled → default on
        val legacy = decodeSettingsBackup("""{"v":1,"callsign":"N0CALL"}""")
        assertEquals(true, legacy?.autoPowerSaveEnabled)
    }

    @Test
    fun gpsPowerSaveBackoffAndRecover() {
        var s = GpsPowerSaveState(0, 60)
        s = gpsPowerSaveStep(enabled = true, gpsOk = false, state = s, baseMinSec = 60)
        assertEquals(1, s.failStreak)
        assertEquals(60, s.gpsIntervalSec)
        s = gpsPowerSaveStep(enabled = true, gpsOk = false, state = s, baseMinSec = 60)
        assertEquals(2, s.failStreak)
        s = gpsPowerSaveStep(enabled = true, gpsOk = false, state = s, baseMinSec = 60)
        assertEquals(0, s.failStreak)
        assertEquals(90, s.gpsIntervalSec)

        // bump until cap
        repeat(20) {
            repeat(3) {
                s = gpsPowerSaveStep(enabled = true, gpsOk = false, state = s, baseMinSec = 60)
            }
        }
        assertEquals(300, s.gpsIntervalSec)

        s = gpsPowerSaveStep(enabled = true, gpsOk = true, state = s, baseMinSec = 60)
        assertEquals(0, s.failStreak)
        assertEquals(60, s.gpsIntervalSec)

        // disabled ignores failures
        s = GpsPowerSaveState(2, 90)
        s = gpsPowerSaveStep(enabled = false, gpsOk = false, state = s, baseMinSec = 60)
        assertEquals(0, s.failStreak)
        assertEquals(60, s.gpsIntervalSec)

        // min already ≥300s → no backoff
        s = GpsPowerSaveState(2, 600)
        repeat(5) {
            s = gpsPowerSaveStep(enabled = true, gpsOk = false, state = s, baseMinSec = 600)
        }
        assertEquals(0, s.failStreak)
        assertEquals(600, s.gpsIntervalSec)
    }
}
