package dev.camilo.st2mode

import android.content.Context
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.BatteryLevels

/**
 * Selected bonded audio address and last-known ANC mode.
 *
 * Isolated from `confirmed_ble_endpoints`, which maps that audio address to the
 * GAIA BLE control address. Widget code should read this store, then look up
 * the endpoint in `confirmed_ble_endpoints`.
 */
class St2SelectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSelectedAddress(): String? =
        prefs.getString(KEY_SELECTED_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedAddress(address: String) {
        prefs.edit().putString(KEY_SELECTED_ADDRESS, address).apply()
    }

    fun loadLastKnownMode(): AncMode? =
        decodeLastKnownMode(prefs.getString(KEY_LAST_MODE, null))

    fun saveLastKnownMode(mode: AncMode) {
        prefs.edit().putString(KEY_LAST_MODE, encodeLastKnownMode(mode)).apply()
    }

    fun clearLastKnownMode() {
        prefs.edit().remove(KEY_LAST_MODE).apply()
    }

    fun loadBatteryLevels(address: String, now: Long = System.currentTimeMillis()): BatteryLevels? {
        val key = "battery_${address.uppercase(java.util.Locale.ROOT)}"
        val at = prefs.getLong("${key}_at", 0L)
        if (at == 0L) return null
        return BatteryLevels(
            prefs.getInt("${key}_left", -1).takeIf { it in 0..100 },
            prefs.getInt("${key}_right", -1).takeIf { it in 0..100 },
            at,
        ).takeIf { it.isRecent(now) }
    }

    fun saveBatteryLevels(address: String, levels: BatteryLevels) {
        val key = "battery_${address.uppercase(java.util.Locale.ROOT)}"
        prefs.edit()
            .putInt("${key}_left", levels.left ?: -1)
            .putInt("${key}_right", levels.right ?: -1)
            .putLong("${key}_at", levels.measuredAt)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "st2_selection"
        const val KEY_SELECTED_ADDRESS = "selected_bonded_address"
        const val KEY_LAST_MODE = "last_known_mode"
        const val CONFIRMED_ENDPOINTS_PREFS = "confirmed_ble_endpoints"
    }
}
