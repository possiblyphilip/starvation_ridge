package com.starvationridge.pit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starvationridge.pit.engine.Phase
import com.starvationridge.pit.engine.RaceRepository
import com.starvationridge.pit.engine.LapRecord
import com.starvationridge.pit.engine.displayedLapOf
import com.starvationridge.pit.engine.formatDuration
import com.starvationridge.pit.engine.lapLogNewestFirst
import com.starvationridge.pit.engine.raceElapsedOf
import kotlinx.coroutines.delay

private val Ink = Color(0xFF1A1200)
private val Amber = Color(0xFFFF6600)
private val Dim = Color(0xFF8A8A8A)

@Composable
fun RaceScreen(repository: RaceRepository, onOpenSettings: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val inPit = state.phase == Phase.InPit
    val pitRunning = inPit && state.pitStartedAtMs != null
    val clockNow = state.raceFinishedAtMs ?: now
    val topStart = if (inPit) state.pitStartedAtMs else state.rideStartedAtMs
    val topElapsed = if (topStart == null) 0L else (clockNow - topStart).coerceAtLeast(0L)
    val rideStart = state.rideStartedAtMs
    val waitingForMinLap = state.phase == Phase.OnCourse &&
        rideStart != null &&
        now - rideStart < state.settings.minLapMs &&
        state.rejected.any { it.atMs >= rideStart }
    val hint = when {
        !status.isNullOrBlank() -> status
        state.settings.beaconMac.isBlank() && state.settings.beaconUuid.isBlank() -> "Choose the bike beacon in Setup"
        waitingForMinLap -> "Beacon is here. Next pit opens after the minimum lap."
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (pitRunning) Amber else Color.Black),
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = if (inPit) "PIT" else "LAP",
                    color = if (pitRunning) Ink else Dim,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    BigNumber(
                        text = formatDuration(topElapsed),
                        color = if (pitRunning) Ink else Color.White,
                        digits = 7,
                    )
                }
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text("Setup", color = if (pitRunning) Ink else Color.White, fontSize = 18.sp)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "LAP",
                color = Dim,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BigNumber(
                    text = displayedLapOf(state).toString(),
                    color = Color.White,
                    digits = 2,
                )
            }
            if (hint != null) {
                Text(
                    text = hint,
                    color = Amber,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            val raceTime = formatDuration(raceElapsedOf(state, now))
            when {
                state.phase == Phase.NotStarted -> StartBand(onStart = { repository.start(System.currentTimeMillis()) })
                state.raceFinishedAtMs != null -> FinishBand(raceTime, state.laps)
                state.phase == Phase.OnCourse -> RaceClock(raceTime, "RACE")
                else -> LapLog(repository)
            }
        }
    }
}

@Composable
private fun StartBand(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "RACE",
            color = Dim,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BigNumber(text = formatDuration(0), color = Color.White, digits = 7)
        }
        Button(
            onClick = onStart,
            modifier = Modifier
                .padding(start = 32.dp, end = 32.dp, bottom = 28.dp)
                .fillMaxWidth()
                .height(88.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Text("Start", fontSize = 40.sp)
        }
    }
}

@Composable
private fun RaceClock(text: String, label: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = if (label == "FINISH") Amber else Dim,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BigNumber(text = text, color = Color.White, digits = 7)
        }
    }
}

@Composable
private fun FinishBand(raceTime: String, laps: List<LapRecord>) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(if (laps.isEmpty()) 1f else 0.45f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "FINISH",
                color = Amber,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BigNumber(text = raceTime, color = Color.White, digits = 7)
            }
        }
        if (laps.isNotEmpty()) {
            LapRows(laps, Modifier.weight(0.55f).fillMaxWidth())
        }
    }
}

@Composable
private fun LapLog(repository: RaceRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "RIDE / PIT",
            color = Dim,
            fontSize = 18.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 12.dp, bottom = 8.dp),
        )
        LapRows(state.laps, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun LapRows(laps: List<LapRecord>, modifier: Modifier) {
    val rows = lapLogNewestFirst(laps)
    if (rows.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No laps yet", color = Dim, fontSize = 28.sp)
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { "${it.lapNumber}-${it.arrivedAtMs}" }) { lap ->
                val pit = lap.pitDurationMs?.let { formatDuration(it) } ?: "—"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = lap.lapNumber.toString(),
                        color = Dim,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "${formatDuration(lap.rideDurationMs)}  /  $pit",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun BigNumber(text: String, color: Color, digits: Int) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val byWidth = maxWidth.value / (digits * 0.7f)
        val byHeight = maxHeight.value * 0.72f
        Text(
            text = text,
            color = color,
            fontSize = minOf(byWidth, byHeight).sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
