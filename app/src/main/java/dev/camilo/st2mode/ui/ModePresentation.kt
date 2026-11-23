package dev.camilo.st2mode.ui

import androidx.annotation.DrawableRes
import dev.camilo.st2mode.R
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.ClientState

/**
 * A cached value may explain the sign, but can never select a live mode button.
 * A mode just written over the audio link selects its button as [sentMode], not live.
 */
internal data class ModePresentation(
    val displayedMode: AncMode?,
    val liveMode: AncMode?,
    val changing: Boolean,
    val controlsEnabled: Boolean,
    val sentMode: AncMode? = null,
) {
    val isLive: Boolean get() = liveMode != null
    val selectedMode: AncMode? get() = liveMode ?: sentMode
}

/**
 * Without BLE, mode buttons stay usable while idle: a tap goes over the audio link,
 * or starts a BLE connection. [directSending] covers that tap until it settles.
 */
internal fun modePresentation(state: ClientState, directSending: Boolean = false): ModePresentation {
    val live = state.currentMode.takeIf { state.ready }
    val sent = state.directMode.takeIf { !state.ready }
    val changing = (state.ready && state.status == "sending") || directSending
    val idle = !state.ready && state.selectedAddress != null &&
        (state.status == "disconnected" || state.status == "error")
    return ModePresentation(
        displayedMode = live ?: sent ?: state.lastKnownMode,
        liveMode = live,
        changing = changing,
        controlsEnabled = (state.ready || idle) && !changing,
        sentMode = sent,
    )
}

@DrawableRes
internal fun modeIcon(mode: AncMode?): Int = when (mode) {
    AncMode.Off -> R.drawable.ic_mode_off
    AncMode.Anc -> R.drawable.ic_mode_anc
    AncMode.Transparency -> R.drawable.ic_mode_transparency
    AncMode.Wind, null -> R.drawable.ic_mode_unknown
}
