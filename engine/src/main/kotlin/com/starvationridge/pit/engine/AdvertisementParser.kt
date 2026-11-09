package com.starvationridge.pit.engine

/**
 * A packet the setup list should show. Classic iBeacon fields are filled when
 * the advertisement carries them. A KBeacon system packet has a name and no UUID.
 */
data class BeaconSighting(
    val name: String,
    val uuid: String,
    val major: Int,
    val minor: Int,
)

/**
 * Walks a BLE advertisement. The KBeacon config app finds a BC05 by the
 * 0x2080 service, the KKM manufacturer id, or an Apple iBeacon. Manufacturer
 * data alone misses the service packet those beacons send while idle.
 */
object AdvertisementParser {
    private const val UUID_KBEACON = 0x2080
    private const val UUID_EDDYSTONE = 0xFEAA
    private val EDDYSTONE_FRAMES = setOf(0x00, 0x10, 0x20, 0x21, 0x22, 0x30)

    fun parse(raw: ByteArray?): BeaconSighting? {
        if (raw == null || raw.isEmpty()) return null
        var name = ""
        var uuid = ""
        var major = -1
        var minor = -1
        var beacon = false
        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            if (length == 0) break
            if (index + length >= raw.size) break
            val type = raw[index + 1].toInt() and 0xFF
            val start = index + 2
            val end = index + 1 + length
            when (type) {
                0x08, 0x09 -> {
                    name = String(raw, start, end - start, Charsets.UTF_8)
                        .trim { it <= ' ' || it == '\u0000' }
                }
                0x02, 0x03 -> beacon = beacon || contains16(raw, start, end)
                0x06, 0x07 -> beacon = beacon || contains128(raw, start, end)
                0x16 -> beacon = beacon || serviceData16(raw, start, end)
                0xFF -> {
                    val ibeacon = manufacturer(raw, start, end)
                    if (ibeacon != null) {
                        beacon = true
                        uuid = ibeacon.uuid
                        major = ibeacon.major
                        minor = ibeacon.minor
                    } else if (isKkm(raw, start, end)) {
                        beacon = true
                    }
                }
            }
            index += length + 1
        }
        if (!beacon && !looksLikeBeaconName(name)) return null
        return BeaconSighting(name, uuid, major, minor)
    }

    private fun contains16(raw: ByteArray, start: Int, end: Int): Boolean {
        var cursor = start
        while (cursor + 1 < end) {
            if (isBeaconService(u16le(raw[cursor], raw[cursor + 1]))) return true
            cursor += 2
        }
        return false
    }

    private fun contains128(raw: ByteArray, start: Int, end: Int): Boolean {
        var cursor = start
        while (cursor + 15 < end) {
            val uuid16 = u16le(raw[cursor + 12], raw[cursor + 13])
            if (isBeaconService(uuid16)) return true
            cursor += 16
        }
        return false
    }

    private fun serviceData16(raw: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < 2) return false
        val uuid = u16le(raw[start], raw[start + 1])
        if (uuid == UUID_KBEACON) return true
        if (uuid != UUID_EDDYSTONE || end - start < 3) return false
        return (raw[start + 2].toInt() and 0xFF) in EDDYSTONE_FRAMES
    }

    private fun manufacturer(raw: ByteArray, start: Int, end: Int): IBeacon? {
        if (end - start < 2) return null
        val company = u16le(raw[start], raw[start + 1])
        if (company != IBeaconParser.APPLE_COMPANY_ID) return null
        return IBeaconParser.parse(company, raw.copyOfRange(start + 2, end))
    }

    private fun isKkm(raw: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < 2) return false
        return u16le(raw[start], raw[start + 1]) == IBeaconParser.KKM_COMPANY_ID
    }

    private fun isBeaconService(uuid16: Int): Boolean = uuid16 == UUID_KBEACON

    private fun looksLikeBeaconName(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("KB") || upper.startsWith("BC") || upper.contains("BEACON")
    }

    private fun u16le(lo: Byte, hi: Byte): Int {
        return (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
    }
}
