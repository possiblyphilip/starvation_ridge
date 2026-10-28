package com.starvationridge.pit.engine

enum class Phase {
    NotStarted,
    OnCourse,
    InPit,
}

data class RaceSettings(
    val enterRssi: Int = -60,
    val exitRssi: Int = -75,
    val enterHoldMs: Long = 2_000L,
    val exitHoldMs: Long = 15_000L,
    val medianWindowMs: Long = 3_000L,
    val minLapMs: Long = 30L * 60L * 1000L,
    val beaconUuid: String = "",
    val beaconMajor: Int = -1,
    val beaconMinor: Int = -1,
)

data class LapRecord(
    val lapNumber: Int,
    val arrivedAtMs: Long,
    val rideDurationMs: Long,
    val pitDurationMs: Long?,
)

data class RejectedSighting(
    val atMs: Long,
    val rssi: Int,
    val reason: String,
)

data class RaceState(
    val phase: Phase = Phase.NotStarted,
    val raceStartedAtMs: Long? = null,
    val pitStartedAtMs: Long? = null,
    val rideStartedAtMs: Long? = null,
    val stagingGraceUntilMs: Long? = null,
    val laps: List<LapRecord> = emptyList(),
    val rejected: List<RejectedSighting> = emptyList(),
    val settings: RaceSettings = RaceSettings(),
)

data class SeenBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val seenAtMs: Long,
)
