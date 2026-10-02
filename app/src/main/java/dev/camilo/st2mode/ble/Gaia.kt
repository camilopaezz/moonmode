package dev.camilo.st2mode.ble

import java.util.UUID

/**
 * BLE GAIA V3 (no 0xFF classic header).
 * Frame: [vendor 2B BE][commandValue 2B BE][payload...]
 * commandValue = (feature << 9) | (type << 7) | command
 */
object Gaia {
    const val VENDOR = 0x001D

    const val TYPE_COMMAND = 0
    const val TYPE_NOTIFICATION = 1
    const val TYPE_RESPONSE = 2

    const val FEATURE_BASIC = 0
    const val FEATURE_AUDIO_CURATION = 8
    const val FEATURE_BATTERY = 13
    const val CMD_GET_BATTERY_LEVELS = 1
    const val GET_BATTERY_LEVELS_RESPONSE = 0x1B01
    const val GET_BATTERY_LEVELS_NOTIFICATION = 0x1A81
    const val GET_BATTERY_LEVELS_ERROR = 0x1B81

    const val CMD_GET_SUPPORTED_FEATURES = 1
    const val CMD_GET_CURRENT_MODE = 3
    const val CMD_SET_MODE = 4

    const val GET_CURRENT_MODE = 0x1003
    const val SET_MODE = 0x1004
    const val GET_CURRENT_MODE_RESPONSE = 0x1103
    const val GET_CURRENT_MODE_NOTIFICATION = 0x1083
    const val SET_MODE_RESPONSE = 0x1104
    const val SET_MODE_NOTIFICATION = 0x1084

    val SERVICE: UUID = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
    val CHAR_COMMAND: UUID = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
    val CHAR_RESPONSE: UUID = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
    val CHAR_DATA: UUID = UUID.fromString("00001103-d102-11e1-9b23-00025b00a5a5")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun commandValue(feature: Int, type: Int, command: Int): Int =
        (feature shl 9) or (type shl 7) or command

    fun frame(commandValue: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = (VENDOR ushr 8).toByte()
        out[1] = VENDOR.toByte()
        out[2] = (commandValue ushr 8).toByte()
        out[3] = commandValue.toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 4, payload.size)
        }
        return out
    }

    fun getSupportedFeatures(): ByteArray =
        frame(commandValue(FEATURE_BASIC, TYPE_COMMAND, CMD_GET_SUPPORTED_FEATURES))

    fun getCurrentMode(): ByteArray =
        frame(commandValue(FEATURE_AUDIO_CURATION, TYPE_COMMAND, CMD_GET_CURRENT_MODE))

    fun getBatteryLevels(): ByteArray =
        frame(commandValue(FEATURE_BATTERY, TYPE_COMMAND, CMD_GET_BATTERY_LEVELS), byteArrayOf(1, 2))

    fun setMode(setCode: Int): ByteArray =
        frame(
            commandValue(FEATURE_AUDIO_CURATION, TYPE_COMMAND, CMD_SET_MODE),
            byteArrayOf(setCode.toByte()),
        )

    data class Packet(
        val vendor: Int,
        val commandValue: Int,
        val payload: ByteArray,
    )

    fun parse(bytes: ByteArray): Packet? {
        if (bytes.size < 4) return null
        val vendor = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (vendor != VENDOR) return null
        val cv = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val payload = if (bytes.size > 4) bytes.copyOfRange(4, bytes.size) else byteArrayOf()
        return Packet(vendor, cv, payload)
    }

    fun isGetCurrentModeRx(commandValue: Int): Boolean =
        commandValue == GET_CURRENT_MODE_RESPONSE || commandValue == GET_CURRENT_MODE_NOTIFICATION

    fun isSetModeRx(commandValue: Int): Boolean =
        commandValue == SET_MODE_RESPONSE || commandValue == SET_MODE_NOTIFICATION

    /** Only GET responses/notifications have a known mode payload; SET events may carry status. */
    fun isModeReportRx(commandValue: Int): Boolean = isGetCurrentModeRx(commandValue)
}
