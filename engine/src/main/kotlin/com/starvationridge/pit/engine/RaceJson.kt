package com.starvationridge.pit.engine

import org.json.JSONArray
import org.json.JSONObject

object RaceJson {
    fun encode(state: RaceState): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("phase", state.phase.name)
        root.put("raceStartedAtMs", state.raceStartedAtMs ?: JSONObject.NULL)
        root.put("pitStartedAtMs", state.pitStartedAtMs ?: JSONObject.NULL)
        root.put("rideStartedAtMs", state.rideStartedAtMs ?: JSONObject.NULL)
        root.put("stagingGraceUntilMs", state.stagingGraceUntilMs ?: JSONObject.NULL)
        root.put("laps", JSONArray().apply {
            state.laps.forEach { lap ->
                put(JSONObject().apply {
                    put("lapNumber", lap.lapNumber)
                    put("arrivedAtMs", lap.arrivedAtMs)
                    put("rideDurationMs", lap.rideDurationMs)
                    if (lap.pitDurationMs == null) put("pitDurationMs", JSONObject.NULL)
                    else put("pitDurationMs", lap.pitDurationMs)
                })
            }
        })
        root.put("rejected", JSONArray().apply {
            state.rejected.forEach { sighting ->
                put(JSONObject().apply {
                    put("atMs", sighting.atMs)
                    put("rssi", sighting.rssi)
                    put("reason", sighting.reason)
                })
            }
        })
        root.put("settings", encodeSettings(state.settings))
        return root.toString(2)
    }

    fun decode(text: String): RaceState {
        val root = JSONObject(text)
        val lapsJson = root.optJSONArray("laps") ?: JSONArray()
        val laps = buildList {
            for (i in 0 until lapsJson.length()) {
                val lap = lapsJson.getJSONObject(i)
                add(
                    LapRecord(
                        lapNumber = lap.getInt("lapNumber"),
                        arrivedAtMs = lap.getLong("arrivedAtMs"),
                        rideDurationMs = lap.getLong("rideDurationMs"),
                        pitDurationMs = if (lap.isNull("pitDurationMs")) null else lap.getLong("pitDurationMs"),
                    )
                )
            }
        }
        val rejectedJson = root.optJSONArray("rejected") ?: JSONArray()
        val rejected = buildList {
            for (i in 0 until rejectedJson.length()) {
                val sighting = rejectedJson.getJSONObject(i)
                add(
                    RejectedSighting(
                        atMs = sighting.getLong("atMs"),
                        rssi = sighting.getInt("rssi"),
                        reason = sighting.optString("reason"),
                    )
                )
            }
        }
        return RaceState(
            phase = Phase.valueOf(root.optString("phase", Phase.NotStarted.name)),
            raceStartedAtMs = optionalLong(root, "raceStartedAtMs"),
            pitStartedAtMs = optionalLong(root, "pitStartedAtMs"),
            rideStartedAtMs = optionalLong(root, "rideStartedAtMs"),
            stagingGraceUntilMs = optionalLong(root, "stagingGraceUntilMs"),
            laps = laps,
            rejected = rejected,
            settings = decodeSettings(root.optJSONObject("settings")),
        )
    }

    private fun encodeSettings(settings: RaceSettings): JSONObject {
        return JSONObject().apply {
            put("enterRssi", settings.enterRssi)
            put("exitRssi", settings.exitRssi)
            put("enterHoldMs", settings.enterHoldMs)
            put("exitHoldMs", settings.exitHoldMs)
            put("medianWindowMs", settings.medianWindowMs)
            put("minLapMs", settings.minLapMs)
            put("beaconUuid", settings.beaconUuid)
            put("beaconMajor", settings.beaconMajor)
            put("beaconMinor", settings.beaconMinor)
        }
    }

    private fun decodeSettings(json: JSONObject?): RaceSettings {
        if (json == null) return RaceSettings()
        val defaults = RaceSettings()
        return RaceSettings(
            enterRssi = json.optInt("enterRssi", defaults.enterRssi),
            exitRssi = json.optInt("exitRssi", defaults.exitRssi),
            enterHoldMs = json.optLong("enterHoldMs", defaults.enterHoldMs),
            exitHoldMs = json.optLong("exitHoldMs", defaults.exitHoldMs),
            medianWindowMs = json.optLong("medianWindowMs", defaults.medianWindowMs),
            minLapMs = json.optLong("minLapMs", defaults.minLapMs),
            beaconUuid = json.optString("beaconUuid", ""),
            beaconMajor = json.optInt("beaconMajor", -1),
            beaconMinor = json.optInt("beaconMinor", -1),
        )
    }

    private fun optionalLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.getLong(key)
    }
}
