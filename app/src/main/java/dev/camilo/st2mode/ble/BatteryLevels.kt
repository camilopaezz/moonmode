package dev.camilo.st2mode.ble

data class BatteryLevels(val left: Int?, val right: Int?, val measuredAt: Long) {
    fun isRecent(now: Long): Boolean = now - measuredAt in 0..MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

/** GAIA V3 battery data is ID/percentage pairs, with FF meaning unknown. */
internal fun decodeBatteryReport(value: ByteArray, measuredAt: Long): BatteryLevels? {
    val packet = Gaia.parse(value) ?: return null
    if (packet.commandValue != Gaia.GET_BATTERY_LEVELS_RESPONSE &&
        packet.commandValue != Gaia.GET_BATTERY_LEVELS_NOTIFICATION) return null
    val payload = packet.payload
    if (payload.isEmpty() || payload.size % 2 != 0) return null
    val levels = mutableMapOf<Int, Int?>()
    for (i in payload.indices step 2) {
        val id = payload[i].toInt() and 0xFF
        if (id != 1 && id != 2) continue
        val level = payload[i + 1].toInt() and 0xFF
        if (id in levels || (level > 100 && level != 255)) return null
        levels[id] = level.takeIf { it != 255 }
    }
    if (levels.isEmpty()) return null
    return BatteryLevels(levels[1], levels[2], measuredAt)
}
