package dev.camilo.st2mode

import dev.camilo.st2mode.ble.AncMode

/**
 * Pure selection / last-mode helpers for [St2SelectionStore] and the widget service.
 *
 * Last-known [AncMode] is a cache of the last accepted GET or successful SET. It is
 * not a live reading: UI and widgets must not present it as current while GATT is
 * disconnected.
 */
data class St2WidgetTarget(
    val bondedAddress: String,
    val bleEndpointAddress: String,
)

fun restoreBondedSelection(
    savedAddress: String?,
    bondedAddresses: Collection<String>,
): String? {
    if (savedAddress.isNullOrBlank()) return null
    return bondedAddresses.firstOrNull { it.equals(savedAddress, ignoreCase = true) }
}

fun resolveBondedSelection(
    inMemoryAddress: String?,
    savedAddress: String?,
    bondedAddresses: Collection<String>,
): String? =
    restoreBondedSelection(inMemoryAddress, bondedAddresses)
        ?: restoreBondedSelection(savedAddress, bondedAddresses)

fun encodeLastKnownMode(mode: AncMode): String = mode.name

fun decodeLastKnownMode(saved: String?): AncMode? {
    if (saved.isNullOrBlank()) return null
    return AncMode.entries.firstOrNull { it.name == saved }
}

/**
 * Widget FGS may connect only when a persisted audio address is still bonded
 * and that address has a confirmed BLE endpoint. Otherwise open [MainActivity].
 */
fun widgetTargetOrNull(
    savedBondedAddress: String?,
    bondedAddresses: Collection<String>,
    confirmedEndpoint: (bondedAddress: String) -> String?,
): St2WidgetTarget? {
    val bonded = restoreBondedSelection(savedBondedAddress, bondedAddresses) ?: return null
    val endpoint = confirmedEndpoint(bonded)?.takeIf { it.isNotBlank() } ?: return null
    return St2WidgetTarget(bondedAddress = bonded, bleEndpointAddress = endpoint)
}

/** Modes the home-screen widget may send. Wind is not a widget action. */
fun widgetModes(): List<AncMode> = listOf(AncMode.Off, AncMode.Anc, AncMode.Transparency)

fun decodeWidgetMode(saved: String?): AncMode? =
    decodeLastKnownMode(saved)?.takeIf { it in widgetModes() }
