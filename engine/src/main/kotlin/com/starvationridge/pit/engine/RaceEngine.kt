package com.starvationridge.pit.engine

/**
 * Lap timing for one bike and one pit tablet.
 *
 * The displayed lap is the lap the rider is on. It stays on that number through
 * the pit ("the lap you just rolled in on") and advances when the bike leaves.
 * A lap row is created on arrival. Its pit time is filled in on departure.
 *
 * Call from one thread.
 */
class RaceEngine(initial: RaceState = RaceState()) {
    var state: RaceState = initial
        private set

    private val samples = ArrayDeque<Sample>()
    private var strongSince: Long? = null
    private var lastStrongAt = 0L
    private var weakSince: Long? = null
    private var rejectLatched = false
    private var dropRequired = false
    private var dropRequiredSince = 0L
    private val undoStack = ArrayDeque<RaceState>()

    fun displayedLap(): Int = displayedLapOf(state)

    fun pitElapsed(now: Long): Long {
        val start = state.pitStartedAtMs ?: return 0L
        return (now - start).coerceAtLeast(0L)
    }

    fun raceElapsed(now: Long): Long = raceElapsedOf(state, now)

    fun start(now: Long) {
        if (state.phase != Phase.NotStarted) return
        trim(now)
        pushUndo()
        val med = freshMedian(now)
        val strong = med != null && med >= state.settings.enterRssi
        state = if (strong) {
            state.copy(
                phase = Phase.InPit,
                raceStartedAtMs = now,
                pitStartedAtMs = now,
                rideStartedAtMs = null,
                stagingGraceUntilMs = null,
                beaconHeardInPit = true,
            )
        } else {
            state.copy(
                phase = Phase.OnCourse,
                raceStartedAtMs = now,
                pitStartedAtMs = null,
                rideStartedAtMs = now,
                stagingGraceUntilMs = now + STAGING_GRACE_MS,
            )
        }
        strongSince = null
        lastStrongAt = 0L
        weakSince = null
        rejectLatched = false
    }

    fun sample(rssi: Int, now: Long) {
        if (state.raceFinishedAtMs != null) return
        samples.addLast(Sample(now, rssi))
        trim(now)
        evaluate(now)
    }

    fun tick(now: Long) {
        if (state.raceFinishedAtMs != null) return
        trim(now)
        evaluate(now)
    }

    fun manualArrive(now: Long): String {
        if (state.raceFinishedAtMs != null) return "Race clock is already stopped"
        if (state.phase == Phase.NotStarted) return "Start the race first"
        if (state.phase != Phase.OnCourse) return "Already in the pit. Lap ${displayedLap()}."
        pushUndo()
        val rideStart = state.rideStartedAtMs ?: now
        val lap = LapRecord(
            lapNumber = displayedLap(),
            arrivedAtMs = now,
            rideDurationMs = (now - rideStart).coerceAtLeast(0L),
            pitDurationMs = null,
        )
        state = state.copy(
            phase = Phase.InPit,
            pitStartedAtMs = now,
            rideStartedAtMs = rideStart,
            stagingGraceUntilMs = null,
            laps = state.laps + lap,
            beaconHeardInPit = freshMedian(now)?.let { it >= state.settings.exitRssi } == true,
        )
        clearPresence()
        dropRequired = false
        return "Arrived. Pit clock is running on lap ${displayedLap()}."
    }

    fun manualLeave(now: Long): String {
        if (state.raceFinishedAtMs != null) return "Race clock is already stopped"
        if (state.phase != Phase.InPit) return "Already out of the pit. Lap ${displayedLap()}."
        leave(now)
        return "Left. Now on lap ${displayedLap()}. Pit clock stopped."
    }

    fun reset(): String {
        val settings = state.settings
        state = RaceState(settings = settings)
        samples.clear()
        undoStack.clear()
        clearPresence()
        dropRequired = false
        return "Race cleared. Beacon setup kept."
    }

    fun finish(now: Long): String {
        if (state.raceStartedAtMs == null) return "Start the race first"
        if (state.raceFinishedAtMs != null) return "Race clock is already stopped"
        pushUndo()
        state = state.copy(raceFinishedAtMs = now)
        return "Race clock stopped at ${formatDuration(raceElapsed(now))}."
    }

    fun undo(now: Long): String {
        if (undoStack.isEmpty()) return "Nothing to undo"
        val prev = undoStack.removeLast()
        val phaseChanged = prev.phase != state.phase
        state = prev.copy(settings = state.settings)
        samples.clear()
        clearPresence()
        if (phaseChanged) {
            dropRequired = true
            dropRequiredSince = now
        }
        return "Undid the last change. Lap ${displayedLap()}."
    }

    fun updateSettings(settings: RaceSettings) {
        val fixed = sanitize(settings)
        if (fixed == state.settings) return
        state = state.copy(settings = fixed)
    }

    fun calibrateAsPit(rssi: Int) {
        val enter = rssi - 6
        updateSettings(state.settings.copy(enterRssi = enter, exitRssi = enter - 15))
    }

    private fun evaluate(now: Long) {
        val med = freshMedian(now)
        when (state.phase) {
            Phase.NotStarted -> Unit
            Phase.OnCourse -> evaluateCourse(now, med)
            Phase.InPit -> evaluatePit(now, med)
        }
    }

    private fun evaluateCourse(now: Long, med: Int?) {
        if (applyStagingGrace(now, med)) return
        if (dropRequired) {
            val quiet = samples.isEmpty() && now - dropRequiredSince >= FRESH_MS
            val dropped = med != null && med < state.settings.exitRssi
            if (dropped || quiet) {
                dropRequired = false
            } else {
                return
            }
        }
        val rssi = med
        if (rssi != null && rssi >= state.settings.enterRssi) {
            val gap = if (lastStrongAt == 0L) Long.MAX_VALUE else now - lastStrongAt
            val newEpisode = strongSince == null || gap > PRESENCE_MS
            if (newEpisode) {
                strongSince = now
                rejectLatched = false
            }
            lastStrongAt = now
            val since = strongSince ?: return
            if (newEpisode || now - since < state.settings.enterHoldMs) return
            val rideStart = state.rideStartedAtMs ?: return
            if (now - rideStart < state.settings.minLapMs) {
                if (!rejectLatched) {
                    rejectLatched = true
                    val sighting = RejectedSighting(now, rssi, "Too soon for another lap")
                    state = state.copy(rejected = (state.rejected + sighting).takeLast(30))
                }
                return
            }
            arrive(now)
        } else if (med != null || lastStrongAt == 0L || now - lastStrongAt > PRESENCE_MS) {
            strongSince = null
            lastStrongAt = 0L
            rejectLatched = false
        }
    }

    /**
     * Packets can show up a moment after Start while the bike is still at the truck.
     * Treat a strong signal in that window as the opening pit, not a finished lap.
     * Returns true when the caller should not also try to record an arrival.
     */
    private fun applyStagingGrace(now: Long, med: Int?): Boolean {
        val grace = state.stagingGraceUntilMs ?: return false
        val strongNow = med != null && med >= state.settings.enterRssi
        if (strongNow) {
            state = state.copy(
                phase = Phase.InPit,
                pitStartedAtMs = now,
                rideStartedAtMs = null,
                stagingGraceUntilMs = null,
                beaconHeardInPit = true,
            )
            clearPresence()
            return true
        }
        if (now >= grace || (med != null && med < state.settings.enterRssi)) {
            state = state.copy(stagingGraceUntilMs = null)
            return false
        }
        return true
    }

    private fun evaluatePit(now: Long, med: Int?) {
        if (med != null && med >= state.settings.exitRssi) {
            if (!state.beaconHeardInPit) state = state.copy(beaconHeardInPit = true)
            weakSince = null
            return
        }
        // Silence is not a departure until the beacon has been heard in this pit.
        // Otherwise a manual Arrived with no beacon ends itself after 15 seconds.
        if (!state.beaconHeardInPit) {
            weakSince = null
            return
        }
        if (weakSince == null) {
            weakSince = if (med == null) samples.lastOrNull()?.atMs ?: now else now
        }
        val since = weakSince ?: return
        if (now - since >= state.settings.exitHoldMs) {
            leave(now)
        }
    }

    private fun arrive(now: Long) {
        pushUndo()
        val rideStart = state.rideStartedAtMs ?: now
        val lap = LapRecord(
            lapNumber = displayedLap(),
            arrivedAtMs = now,
            rideDurationMs = (now - rideStart).coerceAtLeast(0L),
            pitDurationMs = null,
        )
        state = state.copy(
            phase = Phase.InPit,
            pitStartedAtMs = now,
            stagingGraceUntilMs = null,
            beaconHeardInPit = true,
            laps = state.laps + lap,
        )
        clearPresence()
    }

    private fun leave(now: Long) {
        pushUndo()
        val pitStart = state.pitStartedAtMs ?: now
        val laps = state.laps.toMutableList()
        val last = laps.lastOrNull()
        if (last != null && last.pitDurationMs == null) {
            laps[laps.lastIndex] = last.copy(pitDurationMs = (now - pitStart).coerceAtLeast(0L))
        }
        state = state.copy(
            phase = Phase.OnCourse,
            pitStartedAtMs = null,
            rideStartedAtMs = now,
            stagingGraceUntilMs = null,
            beaconHeardInPit = false,
            laps = laps,
        )
        clearPresence()
        dropRequired = false
    }

    private fun freshMedian(now: Long): Int? {
        val newest = samples.lastOrNull() ?: return null
        if (now - newest.atMs > FRESH_MS) return null
        val values = samples.map { it.rssi }.sorted()
        return values[values.size / 2]
    }

    private fun trim(now: Long) {
        val cutoff = now - state.settings.medianWindowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
    }

    private fun clearPresence() {
        strongSince = null
        lastStrongAt = 0L
        weakSince = null
        rejectLatched = false
    }

    private fun pushUndo() {
        undoStack.addLast(state)
        while (undoStack.size > 30) undoStack.removeFirst()
    }

    private fun sanitize(settings: RaceSettings): RaceSettings {
        var exit = settings.exitRssi
        val enter = settings.enterRssi
        if (exit >= enter) exit = enter - 10
        return settings.copy(
            enterRssi = enter,
            exitRssi = exit,
            minLapMs = settings.minLapMs.coerceIn(0L, 240L * 60L * 1000L),
            enterHoldMs = settings.enterHoldMs.coerceAtLeast(0L),
            exitHoldMs = settings.exitHoldMs.coerceAtLeast(0L),
            medianWindowMs = settings.medianWindowMs.coerceAtLeast(1L),
            beaconMac = settings.beaconMac.uppercase(),
            beaconUuid = settings.beaconUuid.uppercase(),
        )
    }

    private data class Sample(val atMs: Long, val rssi: Int)

    companion object {
        const val FRESH_MS = 1_500L
        const val PRESENCE_MS = 12_000L
        const val STAGING_GRACE_MS = 15_000L
    }
}
