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

    fun raceElapsed(now: Long): Long {
        val start = state.raceStartedAtMs ?: return 0L
        return (now - start).coerceAtLeast(0L)
    }

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
        weakSince = null
        rejectLatched = false
    }

    fun sample(rssi: Int, now: Long) {
        samples.addLast(Sample(now, rssi))
        trim(now)
        evaluate(now)
    }

    fun tick(now: Long) {
        trim(now)
        evaluate(now)
    }

    fun manualArrive(now: Long) {
        if (state.phase != Phase.OnCourse) return
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
        )
        clearPresence()
        dropRequired = false
    }

    fun manualLeave(now: Long) {
        if (state.phase != Phase.InPit) return
        leave(now)
    }

    fun undo(now: Long) {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        val phaseChanged = prev.phase != state.phase
        state = prev.copy(settings = state.settings)
        samples.clear()
        clearPresence()
        if (phaseChanged) {
            dropRequired = true
            dropRequiredSince = now
        }
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
        if (med != null && med >= state.settings.enterRssi) {
            touchStrong(now)
            val since = strongSince ?: return
            if (now - since < state.settings.enterHoldMs) return
            val rideStart = state.rideStartedAtMs ?: return
            if (now - rideStart < state.settings.minLapMs) {
                if (!rejectLatched) {
                    rejectLatched = true
                    val sighting = RejectedSighting(now, med, "Too soon for another lap")
                    state = state.copy(rejected = (state.rejected + sighting).takeLast(30))
                }
                return
            }
            arrive(now)
        } else {
            strongSince = null
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
            laps = laps,
        )
        clearPresence()
        dropRequired = false
    }

    private fun touchStrong(now: Long) {
        val prev = if (samples.size >= 2) samples.elementAt(samples.size - 2).atMs else null
        if (strongSince == null || prev == null || now - prev > FRESH_MS) {
            strongSince = now
            if (prev == null || now - prev > FRESH_MS) rejectLatched = false
        }
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
            beaconUuid = settings.beaconUuid.uppercase(),
        )
    }

    private data class Sample(val atMs: Long, val rssi: Int)

    companion object {
        const val FRESH_MS = 1_500L
        const val STAGING_GRACE_MS = 15_000L
    }
}
