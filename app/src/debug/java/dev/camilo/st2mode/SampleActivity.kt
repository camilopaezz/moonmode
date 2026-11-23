package dev.camilo.st2mode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.BleEndpoint
import dev.camilo.st2mode.ble.BondedDevice
import dev.camilo.st2mode.ble.ClientState
import dev.camilo.st2mode.ui.HomeScreen
import dev.camilo.st2mode.ui.SettingsScreen
import dev.camilo.st2mode.ui.theme.St2ModeTheme
import dev.camilo.st2mode.widget.St2ModeWidget

/**
 * Sample data for visual review, launched with
 * `adb shell am start -n dev.camilo.st2mode/.SampleActivity --es screen home|settings
 *  --es state connected|cached|error|setup --es theme light|dark`, or the deep link
 * `moonmode-sample://sample?screen=home&state=connected&theme=dark`. Add `widget=off|anc|transparency`
 * to seed the widget's last-known mode for review.
 */
class SampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fun extra(name: String) = intent.getStringExtra(name) ?: intent.data?.getQueryParameter(name)
        val screen = extra("screen") ?: "home"
        val theme = when (extra("theme")) {
            "light" -> ThemePreference.Light
            "dark" -> ThemePreference.Dark
            else -> ThemePreference.System
        }
        val initial = sampleState(extra("state") ?: "connected")
        extra("widget")?.let { name ->
            AncMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { mode ->
                St2SelectionStore(this).saveLastKnownMode(mode)
                St2ModeWidget.updateAll(this)
            }
        }
        setContent {
            var state by remember { mutableStateOf(initial) }
            var preferences by remember { mutableStateOf(SettingsPreferences(theme = theme)) }
            St2ModeTheme(preferences) {
                if (screen == "settings") {
                    SettingsScreen(state, preferences, hasPermissions = true, onBack = ::finish,
                        onSelect = { state = state.copy(selectedAddress = it) }, onReset = {},
                        onUpdate = { preferences = it })
                } else {
                    HomeScreen(state, onSettings = {}, onConnect = {}, onDisconnect = {}, onEndpoint = {},
                        onMode = { state = state.copy(currentMode = it, lastKnownMode = it) })
                }
            }
        }
    }
}

private val sampleDevices = listOf(
    BondedDevice("00:11:22:33:44:55", "Space Travel 2 (sample)", preferred = true),
    BondedDevice("66:77:88:99:AA:BB", "Studio Buds (sample)", preferred = false),
)

private fun sampleState(name: String): ClientState {
    val base = ClientState(bonded = sampleDevices, selectedAddress = sampleDevices.first().address)
    return when (name) {
        "cached" -> base.copy(lastKnownMode = AncMode.Transparency)
        "error" -> base.copy(status = "error", errorMessage = "BLE control device not found",
            lastKnownMode = AncMode.Anc)
        "setup" -> base.copy(status = "select endpoint", endpoints = listOf(
            BleEndpoint("C0:FF:EE:00:00:01", "Space Travel 2"),
            BleEndpoint("C0:FF:EE:00:00:02", "Space Travel 2"),
            BleEndpoint("C0:FF:EE:00:00:03", "Space Travel 2"),
        ))
        else -> base.copy(status = "connected", ready = true, currentMode = AncMode.Anc, lastKnownMode = AncMode.Anc)
    }
}
