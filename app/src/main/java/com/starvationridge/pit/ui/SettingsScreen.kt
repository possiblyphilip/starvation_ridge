package com.starvationridge.pit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starvationridge.pit.engine.RaceRepository
import com.starvationridge.pit.engine.SeenBeacon

private val Amber = Color(0xFFFFB300)
private val Dim = Color(0xFFB0B0B0)

@Composable
fun SettingsScreen(repository: RaceRepository, onClose: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val seen by repository.seen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOk = isBatteryUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val selected = seen.firstOrNull { beacon ->
        repository.matches(beacon.uuid, beacon.major, beacon.minor)
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Setup", color = Color.White, fontSize = 32.sp)
                TextButton(onClick = onClose) {
                    Text("Done", color = Amber, fontSize = 20.sp)
                }
            }

            Text(
                text = if (selected == null) "Not hearing the selected beacon." else "Hearing the bike at ${selected.rssi} dBm",
                color = Color.White,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
            )
            Button(
                onClick = { selected?.let { repository.calibrateAsPit(it.rssi) } },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
            ) {
                Text("This is the pit", fontSize = 20.sp)
            }
            Text(
                text = "In the pit at ${state.settings.enterRssi} dBm or stronger. Gone below ${state.settings.exitRssi} dBm.",
                color = Dim,
                fontSize = 16.sp,
            )
            Stepper(
                label = "Pit signal",
                value = state.settings.enterRssi.toString(),
                onMinus = {
                    repository.setThresholds(state.settings.enterRssi - 1, state.settings.exitRssi)
                },
                onPlus = {
                    repository.setThresholds(state.settings.enterRssi + 1, state.settings.exitRssi)
                },
            )
            Stepper(
                label = "Away signal",
                value = state.settings.exitRssi.toString(),
                onMinus = {
                    repository.setThresholds(state.settings.enterRssi, state.settings.exitRssi - 1)
                },
                onPlus = {
                    val next = state.settings.exitRssi + 1
                    if (next < state.settings.enterRssi) {
                        repository.setThresholds(state.settings.enterRssi, next)
                    }
                },
            )
            Stepper(
                label = "Minimum lap",
                value = "${state.settings.minLapMs / 60_000L} min",
                onMinus = { repository.setMinLapMinutes((state.settings.minLapMs / 60_000L).toInt() - 5) },
                onPlus = { repository.setMinLapMinutes((state.settings.minLapMs / 60_000L).toInt() + 5) },
            )

            Text("Beacons", color = Color.White, fontSize = 22.sp)
            if (seen.isEmpty()) {
                Text("No beacons yet. Bring the bike to the truck.", color = Dim, fontSize = 16.sp)
            } else {
                seen.forEach { beacon ->
                    BeaconRow(
                        beacon = beacon,
                        selected = repository.matches(beacon.uuid, beacon.major, beacon.minor),
                        onClick = { repository.selectBeacon(beacon.uuid, beacon.major, beacon.minor) },
                    )
                }
            }

            Text("If a pass is missed", color = Color.White, fontSize = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { repository.manualArrive(System.currentTimeMillis()) }, modifier = Modifier.weight(1f)) {
                    Text("Arrived")
                }
                Button(onClick = { repository.manualLeave(System.currentTimeMillis()) }, modifier = Modifier.weight(1f)) {
                    Text("Left")
                }
                Button(onClick = { repository.undo(System.currentTimeMillis()) }, modifier = Modifier.weight(1f)) {
                    Text("Undo")
                }
            }
            state.rejected.lastOrNull()?.let { sighting ->
                Text(
                    text = "Ignored a pass at ${sighting.rssi} dBm. Too soon for another lap.",
                    color = Dim,
                    fontSize = 16.sp,
                )
            }

            Button(
                onClick = { shareLapLog(context, repository.lapsCsv()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Share lap log")
            }
            if (batteryOk) {
                Text("Battery optimization is off.", color = Dim, fontSize = 16.sp)
            } else {
                Button(
                    onClick = { requestUnrestrictedBattery(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Keep running on battery")
                }
            }

            Text(
                text = "Leave the beacon motion trigger off so a parked bike keeps advertising. Use one iBeacon, about a 400 ms interval, and start the transmit power at 0 dBm. Lower the power if another loop still shows up at the truck. Put the beacon on the side of the bike that faces this tablet.",
                color = Dim,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Dim, fontSize = 14.sp)
            Text(value, color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = onMinus) { Text("−", color = Color.White, fontSize = 32.sp) }
        TextButton(onClick = onPlus) { Text("+", color = Color.White, fontSize = 32.sp) }
    }
}

@Composable
private fun BeaconRow(beacon: SeenBeacon, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) Amber else Color(0xFF333333)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, border)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(
            text = "Major ${beacon.major}   Minor ${beacon.minor}   ${beacon.rssi} dBm",
            color = Color.White,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(text = beacon.uuid, color = Dim, fontSize = 13.sp)
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pit Lap needs Bluetooth to hear the beacon on the bike.",
            color = Color.White,
            fontSize = 28.sp,
        )
        Button(
            onClick = onAllow,
            modifier = Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
        ) {
            Text("Allow Bluetooth", fontSize = 22.sp)
        }
        TextButton(onClick = onOpenSettings) {
            Text("Open settings", color = Color.White, fontSize = 18.sp)
        }
    }
}
