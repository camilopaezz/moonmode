package dev.camilo.st2mode.widget

import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.BatteryLevels
import dev.camilo.st2mode.widgetModes

/** Status describes a request, never its confirmed result. Only the persisted cache selects a stop. */
internal data class WidgetPresentation(val lastKnownMode: AncMode?, val status: String?) {
    val selectedMode: AncMode? get() = lastKnownMode?.takeIf { it in widgetModes() }
}

internal fun widgetBatteryText(levels: BatteryLevels?, now: Long): String? {
    if (levels == null || !levels.isRecent(now)) return null
    return listOfNotNull(
        levels.left?.takeIf { it > 0 }?.let { "L $it%" },
        levels.right?.takeIf { it > 0 }?.let { "R $it%" },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
}
