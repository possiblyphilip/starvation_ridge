package com.starvationridge.pit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RaceSupportTest {
    @Test
    fun durationFormatCoversAFullDayAndBeyond() {
        assertEquals("0:00:00", formatDuration(0))
        assertEquals("0:00:01", formatDuration(1_499))
        assertEquals("1:01:01", formatDuration(3_661_000))
        assertEquals("25:00:00", formatDuration(25L * 3_600L * 1_000L))
    }

    @Test
    fun lapLogIsNewestFirstAndCsvHasRideAndPit() {
        val laps = listOf(
            LapRecord(1, 123, 3_661_000, 90_000),
            LapRecord(2, 456, 1_000, null),
        )
        assertEquals(listOf(2, 1), lapLogNewestFirst(laps).map { it.lapNumber })
        val csv = lapsToCsv(laps)
        assertTrue(csv.startsWith("lap,ride,pit,arrived_at_ms\n"))
        assertTrue(csv.contains("1,1:01:01,0:01:30,123\n"))
        assertTrue(csv.contains("2,0:00:01,,456\n"))
    }

    @Test
    fun parserReadsAnAppleIBeaconAndRejectsOtherManufacturers() {
        val uuid = hex("E2C56DB5DFFB48D2B060D0F5A71096E0")
        val data = byteArrayOf(0x02, 0x15) + uuid + byteArrayOf(0x00, 0x01, 0x00, 0x02, 0xC5.toByte())
        val beacon = IBeaconParser.parse(IBeaconParser.APPLE_COMPANY_ID, data)
        assertEquals("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", beacon?.uuid)
        assertEquals(1, beacon?.major)
        assertEquals(2, beacon?.minor)
        assertEquals(-59, beacon?.txPower)
        assertNull(IBeaconParser.parse(0x0001, data))
        assertNull(IBeaconParser.parse(IBeaconParser.APPLE_COMPANY_ID, byteArrayOf(0x02, 0x15)))
    }

    @Test
    fun repositoryRestoresTheRaceAndIgnoresOtherBeacons() {
        val dir = Files.createTempDirectory("pit-race").toFile()
        val file = dir.resolve("race.json")
        val repo = RaceRepository(file)
        repo.selectBeacon("e2c56db5-dffb-48d2-b060-d0f5a71096e0", 1, 2)
        repo.setMinLapMinutes(0)
        repo.start(0)
        repo.noteBeacon("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", 1, 2, -40, 500)
        assertEquals(Phase.OnCourse, repo.state.value.phase)

        repo.noteBeacon("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 2, -50, 800)
        assertEquals(Phase.InPit, repo.state.value.phase)
        assertTrue(repo.state.value.laps.isEmpty())
        assertEquals(2, repo.seen.value.size)

        val restored = RaceRepository(file)
        assertEquals(repo.state.value, restored.state.value)
        assertTrue(restored.matches("e2c56db5-dffb-48d2-b060-d0f5a71096e0", 1, 2))
        assertFalse(restored.matches("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 9, 9))
    }

    @Test
    fun corruptSaveStartsAFreshRace() {
        val dir = Files.createTempDirectory("pit-race-bad").toFile()
        val file = dir.resolve("race.json")
        file.writeText("not json")
        val repo = RaceRepository(file)
        assertEquals(Phase.NotStarted, repo.state.value.phase)
    }

    private fun hex(text: String): ByteArray {
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
