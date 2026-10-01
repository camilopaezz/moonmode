package dev.camilo.st2mode.ui

import androidx.annotation.DrawableRes
import dev.camilo.st2mode.R
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.ClientState

/** A cached value may explain the sign, but can never select a live mode button. */
internal data class ModePresentation(
    val displayedMode: AncMode?,
    val liveMode: AncMode?,
    val changing: Boolean,
    val controlsEnabled: Boolean,
) {
    val isLive: Boolean get() = liveMode != null
}

internal fun modePresentation(state: ClientState): ModePresentation {
    val live = state.currentMode.takeIf { state.ready }
    val changing = state.ready && state.status == "sending"
    return ModePresentation(
        displayedMode = live ?: state.lastKnownMode,
        liveMode = live,
        changing = changing,
        controlsEnabled = state.ready && !changing,
    )
}

@DrawableRes
internal fun modeIcon(mode: AncMode?): Int = when (mode) {
    AncMode.Off -> R.drawable.ic_mode_off
    AncMode.Anc -> R.drawable.ic_mode_anc
    AncMode.Transparency -> R.drawable.ic_mode_transparency
    AncMode.Wind, null -> R.drawable.ic_mode_unknown
}
