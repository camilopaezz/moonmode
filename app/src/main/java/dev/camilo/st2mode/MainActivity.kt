package dev.camilo.st2mode

import android.Manifest
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import dev.camilo.st2mode.ble.St2GattClient
import dev.camilo.st2mode.ui.HomeScreen
import dev.camilo.st2mode.ui.SettingsScreen
import dev.camilo.st2mode.ui.common.predictiveBackPreview
import dev.camilo.st2mode.ui.common.screenTransform
import dev.camilo.st2mode.ui.theme.St2ModeTheme
import dev.camilo.st2mode.widget.St2ModeWidget
import dev.camilo.st2mode.widget.St2WidgetRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var client: St2GattClient
    private var sessionHeld = false
    private var foregroundHeld = false
    private var bluetoothAllowed by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        bluetoothAllowed = client.hasPermissions()
        val ok = grants[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            grants[Manifest.permission.BLUETOOTH_SCAN] == true
        if (ok) {
            client.refreshBonded()
        } else {
            // refreshBonded reports permission denied
            client.refreshBonded()
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        St2WidgetRefresh.sync(this@MainActivity)
        client = St2Session.acquire(this)
        bluetoothAllowed = client.hasPermissions()
        sessionHeld = true
        enableEdgeToEdge()
        if (client.hasPermissions()) {
            client.refreshBonded()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
            )
        }
        val settings = AppSettings(this)
        val connectOnOpen = settings.state.value.connectOnOpen
        setContent {
            val preferences by settings.state.collectAsState()
            var showSettings by rememberSaveable { mutableStateOf(false) }
            St2ModeTheme(preferences) {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                val ui by client.state.collectAsState()
                LaunchedEffect(ui.selectedAddress, bluetoothAllowed) {
                    St2Session.connectOnOpenIfNeeded(connectOnOpen)
                }
                // Predictive back shrinks Settings with the gesture; committing plays the
                // backward screen transition from that preview.
                val back = remember { Animatable(0f) }
                var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
                val scope = rememberCoroutineScope()
                val motion = MaterialTheme.motionScheme
                val settle = motion.defaultSpatialSpec<Float>()
                LaunchedEffect(showSettings) { if (showSettings) back.snapTo(0f) }
                PredictiveBackHandler(enabled = showSettings) { progress ->
                    try {
                        progress.collect { event ->
                            backEdge = event.swipeEdge
                            back.snapTo(event.progress)
                        }
                        showSettings = false
                    } catch (cancelled: CancellationException) {
                        scope.launch { back.animateTo(0f, settle) }
                        throw cancelled
                    }
                }
                AnimatedContent(
                    showSettings,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    transitionSpec = { screenTransform(motion, forward = targetState) },
                    label = "screen",
                ) { settingsVisible ->
                    if (settingsVisible) {
                        Box(Modifier.predictiveBackPreview({ back.value }, backEdge)) {
                            SettingsScreen(ui, preferences, bluetoothAllowed,
                                onBack = { showSettings = false }, onSelect = {
                                    client.select(it)
                                }, onReset = {
                                    client.resetSelectedEndpoint()
                                    showSettings = false
                                }, onUpdate = {
                                    settings.update(it)
                                    St2WidgetRefresh.sync(this@MainActivity)
                                })
                        }
                    } else HomeScreen(
                        state = ui,
                        onSettings = { showSettings = true },
                        onConnect = {
                            if (client.hasPermissions()) {
                                client.connect()
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_SCAN,
                                    ),
                                )
                            }
                        },
                        onDisconnect = client::disconnect,
                        onEndpoint = client::confirmEndpoint,
                        onMode = client::setMode,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::client.isInitialized) {
            if (!foregroundHeld) {
                St2Session.activityResumed()
                foregroundHeld = true
            }
            bluetoothAllowed = client.hasPermissions()
            client.setPollingEnabled(true)
            if (client.hasPermissions()) client.refreshBonded()
        }
    }

    override fun onPause() {
        if (foregroundHeld) {
            St2Session.activityPaused()
            foregroundHeld = false
        }
        if (::client.isInitialized) client.setPollingEnabled(false)
        St2ModeWidget.updateAll(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (sessionHeld) {
            client.setPollingEnabled(false)
            St2Session.release()
            sessionHeld = false
        }
        super.onDestroy()
    }
}
