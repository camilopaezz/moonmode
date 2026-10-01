package dev.camilo.st2mode

import android.content.Context
import dev.camilo.st2mode.ble.AncMode

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

    companion object {
        const val PREFS_NAME = "st2_selection"
        const val KEY_SELECTED_ADDRESS = "selected_bonded_address"
        const val KEY_LAST_MODE = "last_known_mode"
        const val CONFIRMED_ENDPOINTS_PREFS = "confirmed_ble_endpoints"
    }
}
