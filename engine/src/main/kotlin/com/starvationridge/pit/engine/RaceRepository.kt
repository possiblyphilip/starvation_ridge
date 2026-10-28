package com.starvationridge.pit.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saves the race on every timing change. Call from one thread.
 */
class RaceRepository(private val file: File) {
    private val engine = RaceEngine(load())
    private val _state = MutableStateFlow(engine.state)
    private val _seen = MutableStateFlow<List<SeenBeacon>>(emptyList())
    private val _status = MutableStateFlow<String?>(null)

    val state: StateFlow<RaceState> = _state.asStateFlow()
    val seen: StateFlow<List<SeenBeacon>> = _seen.asStateFlow()
    val status: StateFlow<String?> = _status.asStateFlow()

    fun start(now: Long) = change { engine.start(now) }

    fun tick(now: Long) = change { engine.tick(now) }

    fun manualArrive(now: Long) = change { engine.manualArrive(now) }

    fun manualLeave(now: Long) = change { engine.manualLeave(now) }

    fun undo(now: Long) = change { engine.undo(now) }

    fun selectBeacon(uuid: String, major: Int, minor: Int) {
        engine.updateSettings(
            engine.state.settings.copy(
                beaconUuid = uuid,
                beaconMajor = major,
                beaconMinor = minor,
            )
        )
        publish()
    }

    fun setThresholds(enterRssi: Int, exitRssi: Int) {
        engine.updateSettings(engine.state.settings.copy(enterRssi = enterRssi, exitRssi = exitRssi))
        publish()
    }

    fun setMinLapMinutes(minutes: Int) {
        val ms = minutes.coerceIn(0, 240).toLong() * 60L * 1000L
        engine.updateSettings(engine.state.settings.copy(minLapMs = ms))
        publish()
    }

    fun calibrateAsPit(rssi: Int) {
        engine.calibrateAsPit(rssi)
        publish()
    }

    fun noteBeacon(uuid: String, major: Int, minor: Int, rssi: Int, now: Long) {
        val without = _seen.value.filterNot { it.sameAs(uuid, major, minor) }
        _seen.value = (without + SeenBeacon(uuid.uppercase(), major, minor, rssi, now))
            .sortedByDescending { it.rssi }
            .take(12)
        if (matches(uuid, major, minor)) {
            change { engine.sample(rssi, now) }
        }
    }

    fun setStatus(message: String?) {
        _status.value = message
    }

    fun matches(uuid: String, major: Int, minor: Int): Boolean {
        val settings = engine.state.settings
        return settings.beaconUuid.isNotBlank() &&
            settings.beaconUuid.equals(uuid, ignoreCase = true) &&
            settings.beaconMajor == major &&
            settings.beaconMinor == minor
    }

    fun lapsCsv(): String = lapsToCsv(engine.state.laps)

    private fun change(block: () -> Unit) {
        block()
        publish()
    }

    private fun publish() {
        val next = engine.state
        if (next == _state.value) return
        _state.value = next
        persist(next)
    }

    private fun load(): RaceState {
        if (!file.exists() || file.length() == 0L) return RaceState()
        return try {
            RaceJson.decode(file.readText())
        } catch (_: Exception) {
            RaceState()
        }
    }

    private fun persist(state: RaceState) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val json = RaceJson.encode(state)
        tmp.writeText(json)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            file.writeText(json)
            tmp.delete()
        }
    }

    private fun SeenBeacon.sameAs(uuid: String, major: Int, minor: Int): Boolean {
        return this.uuid.equals(uuid, ignoreCase = true) && this.major == major && this.minor == minor
    }
}
