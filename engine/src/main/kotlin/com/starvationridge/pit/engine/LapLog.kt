package com.starvationridge.pit.engine

fun displayedLapOf(state: RaceState): Int {
    if (state.raceStartedAtMs == null) return 0
    return state.laps.count { it.pitDurationMs != null } + 1
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}

fun lapLogNewestFirst(laps: List<LapRecord>): List<LapRecord> = laps.asReversed()

fun lapsToCsv(laps: List<LapRecord>): String {
    val body = laps.joinToString(separator = "") { lap ->
        val pit = lap.pitDurationMs?.let { formatDuration(it) } ?: ""
        "${lap.lapNumber},${formatDuration(lap.rideDurationMs)},$pit,${lap.arrivedAtMs}\n"
    }
    return "lap,ride,pit,arrived_at_ms\n$body"
}
