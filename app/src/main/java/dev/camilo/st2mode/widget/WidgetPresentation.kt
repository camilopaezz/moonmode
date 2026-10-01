package dev.camilo.st2mode.widget

import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.widgetModes

/** Status describes a request, never its confirmed result. Only the persisted cache selects a stop. */
internal data class WidgetPresentation(val lastKnownMode: AncMode?, val status: String?) {
    val selectedMode: AncMode? get() = lastKnownMode?.takeIf { it in widgetModes() }
}
