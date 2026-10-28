package com.starvationridge.pit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RaceEngineTest {
    @Test
    fun strongSignalHeldForTwoSecondsRecordsTheRide() {
        val engine = riding(minLapMs = 0)
        val rideStart = engine.state.rideStartedAtMs!!
        val arrived = hold(engine, rideStart + 1_000, rssi = -50)

        assertEquals(Phase.InPit, engine.state.phase)
        assertEquals(1, engine.state.laps.size)
        val lap = engine.state.laps.single()
        assertEquals(1, lap.lapNumber)
        assertEquals(arrived - rideStart, lap.rideDurationMs)
        assertNull(lap.pitDurationMs)
        assertEquals(1, engine.displayedLap())
        assertEquals(0L, engine.pitElapsed(arrived))
        assertEquals(500L, engine.pitElapsed(arrived + 500))
    }

    @Test
    fun weakLoopDoesNotCountAsALap() {
        val engine = riding(minLapMs = 0)
        val rideStart = engine.state.rideStartedAtMs!!
        hold(engine, rideStart + 1_000, rssi = -90, until = rideStart + 10_000)
        hold(engine, rideStart + 11_000, rssi = -70, until = rideStart + 16_000)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
        assertTrue(engine.state.rejected.isEmpty())
    }

    @Test
    fun oneStrongPacketDoesNotCountAsALap() {
        val engine = riding(minLapMs = 0)
        val at = engine.state.rideStartedAtMs!! + 5_000
        engine.sample(-40, at)
        engine.tick(at + 3_000)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
    }

    @Test
    fun arrivalInsideTheMinimumLapIsRejected() {
        val engine = riding(minLapMs = 30L * 60L * 1000L)
        val rideStart = engine.state.rideStartedAtMs!!
        hold(engine, rideStart + 60_000, rssi = -50)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
        assertEquals(1, engine.state.rejected.size)
        assertEquals("Too soon for another lap", engine.state.rejected.single().reason)
    }

    @Test
    fun stillThereWhenTheMinimumLapEndsDoesCount() {
        val minLap = 30L * 60L * 1000L
        val engine = riding(minLapMs = minLap)
        val rideStart = engine.state.rideStartedAtMs!!
        hold(engine, rideStart + 60_000, rssi = -50)
        val arrived = hold(engine, rideStart + minLap, rssi = -50)

        assertEquals(Phase.InPit, engine.state.phase)
        assertEquals(1, engine.state.laps.size)
        assertEquals(arrived - rideStart, engine.state.laps.single().rideDurationMs)
    }

    @Test
    fun shortDropoutDoesNotEndThePit() {
        val engine = riding(minLapMs = 0)
        val arrived = hold(engine, engine.state.rideStartedAtMs!! + 1_000, rssi = -50)
        engine.tick(arrived + RaceEngine.FRESH_MS + 1)
        val back = arrived + 10_000
        engine.tick(back)

        assertEquals(Phase.InPit, engine.state.phase)
        engine.sample(-50, back)
        assertNull(engine.state.laps.last().pitDurationMs)
        assertEquals(back - arrived, engine.pitElapsed(back))
    }

    @Test
    fun signalBelowTheExitThresholdForFifteenSecondsRecordsPitTime() {
        val engine = riding(minLapMs = 0)
        val arrived = hold(engine, engine.state.rideStartedAtMs!! + 1_000, rssi = -50)
        val firstWeak = arrived + 3_001
        val left = hold(engine, firstWeak, rssi = -80, until = firstWeak + 15_000)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertEquals(left - arrived, engine.state.laps.single().pitDurationMs)
        assertEquals(0L, engine.pitElapsed(left))
        assertEquals(2, engine.displayedLap())
        assertEquals(left, engine.state.rideStartedAtMs)
    }

    @Test
    fun signalBetweenTheThresholdsStaysInThePit() {
        val engine = riding(minLapMs = 0)
        val arrived = hold(engine, engine.state.rideStartedAtMs!! + 1_000, rssi = -50)
        hold(engine, arrived + 3_001, rssi = -70, until = arrived + 20_000)

        assertEquals(Phase.InPit, engine.state.phase)
        assertNull(engine.state.laps.single().pitDurationMs)
    }

    @Test
    fun startingBesideTheTruckIsAPitAndNotALap() {
        val engine = RaceEngine(RaceState(settings = RaceSettings(minLapMs = 0)))
        hold(engine, 0, rssi = -50)
        engine.start(2_000)

        assertEquals(Phase.InPit, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
        assertEquals(1, engine.displayedLap())
        assertEquals(3_000L, engine.pitElapsed(5_000))
        assertEquals(2_000L, engine.state.raceStartedAtMs)
    }

    @Test
    fun aBeaconHeardJustAfterStartIsStillTheOpeningPit() {
        val engine = RaceEngine(RaceState(settings = RaceSettings(minLapMs = 0)))
        engine.start(0)
        engine.sample(-50, 400)

        assertEquals(Phase.InPit, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
        assertEquals(1, engine.displayedLap())
        assertNull(engine.state.stagingGraceUntilMs)
    }

    @Test
    fun leavingTheOpeningPitStartsLapOne() {
        val engine = RaceEngine(RaceState(settings = RaceSettings(minLapMs = 0)))
        hold(engine, 0, rssi = -50)
        engine.start(2_000)
        val left = 2_000L + 15_000L
        engine.tick(2_000L + RaceEngine.FRESH_MS + 1)
        engine.tick(left)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
        assertEquals(1, engine.displayedLap())
        assertEquals(left, engine.state.rideStartedAtMs)
        assertEquals(0L, engine.pitElapsed(left))
    }

    @Test
    fun startIsIgnoredOnceTheRaceIsGoing() {
        val engine = riding(minLapMs = 0)
        val started = engine.state.raceStartedAtMs
        engine.start(engine.state.raceStartedAtMs!! + 50_000)
        assertEquals(started, engine.state.raceStartedAtMs)
    }

    @Test
    fun manualArriveAndLeaveBypassTheRadioAndTheLockout() {
        val engine = riding(minLapMs = 30L * 60L * 1000L)
        val rideStart = engine.state.rideStartedAtMs!!
        engine.manualArrive(rideStart + 1_000)
        engine.manualArrive(rideStart + 2_000)

        assertEquals(Phase.InPit, engine.state.phase)
        assertEquals(1_000L, engine.state.laps.single().rideDurationMs)
        engine.manualLeave(rideStart + 4_000)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertEquals(3_000L, engine.state.laps.single().pitDurationMs)
        assertEquals(2, engine.displayedLap())
        engine.manualLeave(rideStart + 5_000)
        assertEquals(1, engine.state.laps.size)
    }

    @Test
    fun undoDropsAFalseArrivalUntilTheSignalGoesAway() {
        val engine = riding(minLapMs = 0)
        val rideStart = engine.state.rideStartedAtMs!!
        val arrived = hold(engine, rideStart + 1_000, rssi = -50)
        engine.updateSettings(engine.state.settings.copy(enterRssi = -55))
        engine.undo(arrived + 100)

        assertEquals(Phase.OnCourse, engine.state.phase)
        assertTrue(engine.state.laps.isEmpty())
        assertEquals(-55, engine.state.settings.enterRssi)
        assertEquals(1, engine.displayedLap())

        hold(engine, arrived + 200, rssi = -50)
        assertEquals(Phase.OnCourse, engine.state.phase)

        val weakFrom = arrived + 6_000
        val weakUntil = hold(engine, weakFrom, rssi = -90, until = weakFrom + 4_000)
        assertEquals(Phase.OnCourse, engine.state.phase)

        val again = hold(engine, weakUntil + 3_001, rssi = -50)
        assertEquals(Phase.InPit, engine.state.phase)
        assertEquals(1, engine.state.laps.size)
        assertEquals(again - rideStart, engine.state.laps.single().rideDurationMs)
    }

    @Test
    fun undoOfStartReturnsToTheStartButton() {
        val engine = RaceEngine()
        engine.start(1_000)
        engine.undo(1_100)
        assertEquals(Phase.NotStarted, engine.state.phase)
        assertEquals(0, engine.displayedLap())
        assertEquals(0L, engine.raceElapsed(2_000))
    }

    @Test
    fun calibrateSetsThePitLineAFewDbBelowTheTruck() {
        val engine = RaceEngine()
        engine.calibrateAsPit(-50)
        assertEquals(-56, engine.state.settings.enterRssi)
        assertEquals(-71, engine.state.settings.exitRssi)
    }

    @Test
    fun exitThresholdIsKeptWeakerThanTheEnterThreshold() {
        val engine = RaceEngine()
        engine.updateSettings(RaceSettings(enterRssi = -70, exitRssi = -50))
        assertEquals(-70, engine.state.settings.enterRssi)
        assertEquals(-80, engine.state.settings.exitRssi)
    }

    @Test
    fun daylightFallbackKeepsTheExtraHourOnTheRaceClock() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val start = pacific.at(2026, 10, 31, 10, 0)
        val sundayTen = pacific.at(2026, 11, 1, 10, 0)
        val engine = RaceEngine()
        engine.start(start)

        assertEquals(25L * 60L * 60L * 1000L, sundayTen - start)
        assertEquals(25L * 60L * 60L * 1000L, engine.raceElapsed(sundayTen))
        assertEquals("25:00:00", formatDuration(engine.raceElapsed(sundayTen)))

        val stillOut = sundayTen + 42L * 60L * 1000L
        assertEquals("25:42:00", formatDuration(engine.raceElapsed(stillOut)))
    }

    @Test
    fun aLapAcrossTheRepeatedHourCountsThatHourOnce() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val firstOneThirty = pacific.atOffset(2026, 11, 1, 1, 30, ZoneOffset.ofHours(-7))
        val secondOneThirty = pacific.atOffset(2026, 11, 1, 1, 30, ZoneOffset.ofHours(-8))
        val engine = RaceEngine(RaceState(settings = RaceSettings(minLapMs = 0)))
        engine.start(firstOneThirty)
        engine.tick(firstOneThirty + RaceEngine.STAGING_GRACE_MS)
        engine.manualArrive(secondOneThirty)

        assertEquals(60L * 60L * 1000L, secondOneThirty - firstOneThirty)
        assertEquals(60L * 60L * 1000L, engine.state.laps.single().rideDurationMs)
        assertEquals("1:00:00", formatDuration(engine.state.laps.single().rideDurationMs))
    }

    @Test
    fun savedRaceRestoresTheOpenPit() {
        val engine = riding(minLapMs = 0)
        val arrived = hold(engine, engine.state.rideStartedAtMs!! + 1_000, rssi = -50)
        val restored = RaceEngine(RaceJson.decode(RaceJson.encode(engine.state)))

        assertEquals(engine.state, restored.state)
        assertEquals(1, restored.displayedLap())
        assertEquals(4_000L, restored.pitElapsed(arrived + 4_000))
        assertEquals(arrived + 4_000 - engine.state.raceStartedAtMs!!, restored.raceElapsed(arrived + 4_000))
    }

    private fun ZoneId.at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), this)
            .toInstant()
            .toEpochMilli()
    }

    private fun ZoneId.atOffset(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        offset: ZoneOffset,
    ): Long {
        return ZonedDateTime.ofLocal(LocalDateTime.of(year, month, day, hour, minute), this, offset)
            .toInstant()
            .toEpochMilli()
    }

    private fun riding(minLapMs: Long): RaceEngine {
        val engine = RaceEngine(RaceState(settings = RaceSettings(minLapMs = minLapMs)))
        hold(engine, 0, rssi = -50)
        engine.start(2_000)
        check(engine.state.phase == Phase.InPit)
        engine.tick(2_000 + RaceEngine.FRESH_MS + 1)
        engine.tick(2_000 + engine.state.settings.exitHoldMs)
        check(engine.state.phase == Phase.OnCourse)
        check(engine.state.laps.isEmpty())
        return engine
    }

    private fun hold(
        engine: RaceEngine,
        from: Long,
        rssi: Int,
        until: Long = from + 2_000L,
        step: Long = 400L,
    ): Long {
        var t = from
        while (t < until) {
            engine.sample(rssi, t)
            t += step
        }
        engine.sample(rssi, until)
        return until
    }
}
