package dev.camilo.st2mode

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.BondedDevice
import dev.camilo.st2mode.ble.ClientState
import dev.camilo.st2mode.ble.St2GattClient
import dev.camilo.st2mode.ui.ModePresentation
import dev.camilo.st2mode.ui.modeIcon
import dev.camilo.st2mode.ui.modePresentation
import dev.camilo.st2mode.ui.theme.St2ModeTheme
import dev.camilo.st2mode.ui.theme.MoonModeBrandStyle
import dev.camilo.st2mode.widget.St2ModeWidget

class MainActivity : ComponentActivity() {
    private lateinit var client: St2GattClient
    private var sessionHeld = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            grants[Manifest.permission.BLUETOOTH_SCAN] == true
        if (ok) {
            client.refreshBonded()
        } else {
            // refreshBonded reports permission denied
            client.refreshBonded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = St2Session.acquire(this)
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
        setContent {
            St2ModeTheme {
                val ui by client.state.collectAsState()
                St2Screen(
                    state = ui,
                    onSelect = client::select,
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

    override fun onResume() {
        super.onResume()
        if (::client.isInitialized) {
            client.setPollingEnabled(true)
            if (client.hasPermissions()) client.refreshBonded()
        }
    }

    override fun onPause() {
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

@Composable
private fun St2Screen(
    state: ClientState,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEndpoint: (String) -> Unit,
    onMode: (AncMode) -> Unit,
) {
    val presentation = modePresentation(state)
    val canConnect = state.status == "disconnected" || state.status == "error"
    val needsSetup = state.status == "select endpoint"
    val hasChoices = state.bonded.size > 1
    val hasError = state.errorMessage != null
    val deviceName = state.bonded.firstOrNull { it.address == state.selectedAddress }
        ?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_device)
    val actionLabel = stringResource(when {
        state.status == "error" -> R.string.retry
        canConnect -> R.string.connect
        state.ready -> R.string.disconnect
        else -> R.string.cancel
    })
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MoonModeBrandStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = {}, enabled = false) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings_coming_soon),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(deviceName, style = MaterialTheme.typography.headlineLarge)
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val statusColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ConnectionStatusDot(
                        connecting = state.status == "connecting" || state.status == "scanning",
                        disconnected = canConnect,
                    )
                    Text(
                        text = if (hasError) stringResource(R.string.disconnected)
                            else connectionStatus(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                val actionModifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                    .heightIn(min = 48.dp)
                if (!hasError && canConnect) {
                    Button(onClick = onConnect, modifier = actionModifier) {
                        Text(actionLabel)
                    }
                } else if (!hasError) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = actionModifier,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        },
        bottomBar = {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                )),
            ) {
                ModeChooser(presentation, onMode)
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) {
            val signMinimumHeight = ((maxHeight - 16.dp) *
                if (needsSetup || hasChoices || hasError) 0.5f else 1f).coerceAtLeast(220.dp)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasError) {
                    item {
                        ConnectionErrorCard(state, onConnect)
                    }
                }
                // Keep normal use centered; setup and large fonts remain scrollable.
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = signMinimumHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        ModeSign(presentation, state.ready)
                    }
                }
                if (needsSetup) {
                    item {
                        Text(
                            stringResource(R.string.choose_endpoint),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(state.endpoints, key = { it.address }) { endpoint ->
                        OutlinedButton(
                            onClick = { onEndpoint(endpoint.address) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(endpoint.name)
                                Text(endpoint.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (hasChoices) {
                    item {
                        Text(
                            stringResource(R.string.paired_devices),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(state.bonded, key = { it.address }) { device ->
                        BondedRow(
                            device = device,
                            selected = device.address == state.selectedAddress,
                            onSelect = { onSelect(device.address) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusDot(connecting: Boolean, disconnected: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val breathing = if (connecting) {
        val transition = rememberInfiniteTransition(label = "Connection breathing")
        val phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "Status dot pulse",
        )
        Modifier.graphicsLayer {
            alpha = 0.4f + 0.6f * phase.value
            scaleX = 0.85f + 0.3f * phase.value
            scaleY = scaleX
        }
    } else Modifier
    Box(
        Modifier.size(6.dp).then(breathing).then(
            if (disconnected) Modifier.border(1.dp, color, CircleShape)
            else Modifier.background(color, CircleShape),
        ),
    )
}

@Composable
private fun ConnectionErrorCard(state: ClientState, onRetry: () -> Unit) {
    val needsPermission = state.errorMessage == "permission denied"
    val needsPairing = !needsPermission && state.bonded.isEmpty()
    val notFound = state.errorMessage == "BLE control device not found"
    val title = stringResource(when {
        needsPermission -> R.string.bluetooth_access_title
        needsPairing -> R.string.pair_earbuds_title
        notFound -> R.string.earbuds_not_found_title
        else -> R.string.connection_error
    })
    val message = stringResource(when {
        needsPermission -> R.string.bluetooth_access_help
        needsPairing -> R.string.pair_earbuds_help
        notFound -> R.string.earbuds_not_found_help
        else -> R.string.connection_error_help
    })
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_connection_error),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (needsPermission) R.string.allow_bluetooth else R.string.retry))
            }
        }
    }
}

@Composable
private fun connectionStatus(state: ClientState): String = stringResource(when {
    state.status == "error" -> R.string.connection_error
    state.status == "sending" && state.ready -> R.string.changing_mode
    state.ready -> R.string.connected
    state.status == "select endpoint" -> R.string.endpoint_needed
    state.status == "scanning" -> R.string.finding_earbuds
    state.status == "connecting" -> R.string.connecting
    else -> R.string.disconnected
})

@Composable
private fun ModeSign(presentation: ModePresentation, ready: Boolean) {
    val caption = when {
        presentation.displayedMode != null && !presentation.isLive -> R.string.last_known_not_live
        presentation.displayedMode == null && ready -> R.string.waiting_for_reading
        presentation.displayedMode == null -> R.string.connect_for_reading
        else -> null
    }
    val label = presentation.displayedMode?.label ?: stringResource(R.string.no_reading)
    val description = if (presentation.isLive) stringResource(R.string.live_mode, label)
        else if (presentation.displayedMode != null) stringResource(R.string.cached_mode, label)
        else stringResource(R.string.no_reading)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(modeIcon(presentation.displayedMode)),
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(120.dp)
                .alpha(if (presentation.isLive) 1f else 0.45f),
        )
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = caption?.let { stringResource(it) }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ModeChooser(presentation: ModePresentation, onMode: (AncMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val trackColor = colors.surfaceVariant
    val selectedColor = colors.primary
    val onSelectedColor = colors.onPrimary
    val onSurfaceColor = colors.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val labelWidth = with(LocalDensity.current) {
            val trackWidth = maxWidth.roundToPx() - 2 * 6.dp.roundToPx() - 2 * 3.dp.roundToPx()
            (trackWidth / 3 - 2 * 4.dp.roundToPx()).coerceAtLeast(1)
        }
        // Fit full labels like the widget, keeping the three icons aligned.
        val labelSize = remember(textMeasurer, labelStyle, labelWidth) {
            (14 downTo 8).firstOrNull { size ->
                widgetModes().all { mode ->
                    !textMeasurer.measure(
                        text = mode.label,
                        style = labelStyle.copy(fontSize = size.sp),
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = labelWidth),
                    ).didOverflowWidth
                }
            } ?: 8
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Surface(
                modifier = Modifier.padding(6.dp),
                color = trackColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup()
                        .padding(3.dp).height(IntrinsicSize.Min),
                ) {
                    widgetModes().forEach { mode ->
                        val selected = presentation.liveMode == mode
                        Surface(
                            color = if (selected) selectedColor else trackColor,
                            contentColor = if (selected) onSelectedColor else onSurfaceColor,
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            val liveDescription = stringResource(R.string.live_selection)
                            Column(
                                modifier = Modifier
                                    .selectable(
                                        selected = selected,
                                        enabled = presentation.controlsEnabled,
                                        role = Role.RadioButton,
                                        onClick = { onMode(mode) },
                                    )
                                    .semantics { if (selected) stateDescription = liveDescription }
                                    .heightIn(min = 96.dp)
                                    .padding(horizontal = 4.dp, vertical = 14.dp)
                                    .alpha(if (presentation.controlsEnabled || presentation.changing) 1f else 0.6f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    painter = painterResource(modeIcon(mode)),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    mode.label,
                                    style = labelStyle.copy(fontSize = labelSize.sp),
                                    textAlign = TextAlign.Center,
                                    softWrap = false,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BondedRow(device: BondedDevice, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
