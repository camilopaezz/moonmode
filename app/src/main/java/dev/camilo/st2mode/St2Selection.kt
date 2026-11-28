package dev.camilo.st2mode

import dev.camilo.st2mode.ble.AncMode

/**
 * Pure selection / last-mode helpers for [St2SelectionStore] and the widget service.
 *
 * Last-known [AncMode] is a cache of the last accepted GET or successful SET. It is
 * not a live reading: UI and widgets must not present it as current while GATT is
 * disconnected.
 */
data class St2WidgetTarget(val bondedAddress: String)

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
 * Widget FGS may connect only when a persisted audio address is still bonded.
 * Otherwise open [MainActivity]. RFCOMM uses that paired address directly; a
 * confirmed BLE endpoint is only needed when RFCOMM is unavailable, and the
 * command reports that failure instead of scanning.
 */
fun widgetTargetOrNull(
    savedBondedAddress: String?,
    bondedAddresses: Collection<String>,
): St2WidgetTarget? =
    restoreBondedSelection(savedBondedAddress, bondedAddresses)?.let(::St2WidgetTarget)

/** Modes the home-screen widget may send. Wind is not a widget action. */
fun widgetModes(): List<AncMode> = listOf(AncMode.Off, AncMode.Anc, AncMode.Transparency)

fun decodeWidgetMode(saved: String?): AncMode? =
    decodeLastKnownMode(saved)?.takeIf { it in widgetModes() }
