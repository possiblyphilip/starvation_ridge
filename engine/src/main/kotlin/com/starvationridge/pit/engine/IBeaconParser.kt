package com.starvationridge.pit.engine

data class IBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val txPower: Int,
)

object IBeaconParser {
    const val APPLE_COMPANY_ID = 0x004C

    fun parse(manufacturerId: Int, data: ByteArray): IBeacon? {
        if (manufacturerId != APPLE_COMPANY_ID) return null
        if (data.size < 23) return null
        if (data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        val uuid = formatUuid(data.copyOfRange(2, 18))
        val major = u16(data[18], data[19])
        val minor = u16(data[20], data[21])
        val txPower = data[22].toInt()
        return IBeacon(uuid, major, minor, txPower)
    }

    private fun u16(hi: Byte, lo: Byte): Int {
        return ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
    }

    private fun formatUuid(bytes: ByteArray): String {
        val hex = bytes.joinToString(separator = "") { "%02X".format(it) }
        return hex.substring(0, 8) + "-" +
            hex.substring(8, 12) + "-" +
            hex.substring(12, 16) + "-" +
            hex.substring(16, 20) + "-" +
            hex.substring(20, 32)
    }
}
