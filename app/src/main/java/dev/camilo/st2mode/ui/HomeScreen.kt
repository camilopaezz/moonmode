package dev.camilo.st2mode.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.camilo.st2mode.R
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.ClientState
import dev.camilo.st2mode.ui.common.ContentCardGap
import dev.camilo.st2mode.ui.common.DisabledContentAlpha
import dev.camilo.st2mode.ui.common.FloatingHeaderGap
import dev.camilo.st2mode.ui.common.FloatingHeaderHeight
import dev.camilo.st2mode.ui.common.FloatingScaffold
import dev.camilo.st2mode.ui.common.GlassSurface
import dev.camilo.st2mode.ui.common.MinTouchTarget
import dev.camilo.st2mode.ui.common.NoticeCardPadding
import dev.camilo.st2mode.ui.common.ScreenGutter
import dev.camilo.st2mode.ui.common.ScrollDirectionThreshold
import dev.camilo.st2mode.ui.common.SectionLabel
import dev.camilo.st2mode.ui.common.SegmentedGroup
import dev.camilo.st2mode.ui.common.SegmentedRow
import dev.camilo.st2mode.ui.common.StatusPulseSpec
import dev.camilo.st2mode.ui.common.contentEnter
import dev.camilo.st2mode.ui.common.contentExit
import dev.camilo.st2mode.ui.common.heroSwap
import dev.camilo.st2mode.ui.common.valueSwap
import dev.camilo.st2mode.ui.common.floatingDockPadding
import dev.camilo.st2mode.ui.common.floatingHeaderPadding
import dev.camilo.st2mode.ui.common.rememberCompactWindow
import dev.camilo.st2mode.ui.common.rememberReducedMotion
import dev.camilo.st2mode.ui.theme.MoonModeBrandStyle
import dev.camilo.st2mode.widgetModes

/** Large title fades out by this much collapse; the header label swaps after [HeaderSwapFraction]. */
private const val TitleFadeFraction = .45f
private const val HeaderSwapFraction = .6f

/** Dock track: 6 dp padding and 4 dp gaps between pill segments. */
private val DockTrackPadding = 6.dp
private val DockSegmentGap = 4.dp
private val DockSegmentPadding = 4.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeScreen(
    state: ClientState,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEndpoint: (String) -> Unit,
    onMode: (AncMode) -> Unit,
) {
    val presentation = modePresentation(state)
    val canConnect = state.status == "disconnected" || state.status == "error"
    val needsSetup = state.status == "select endpoint"
    val hasError = state.errorMessage != null
    val deviceName = state.bonded.firstOrNull { it.address == state.selectedAddress }
        ?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_device)
    val compact = rememberCompactWindow()
    val reducedMotion = rememberReducedMotion()
    val scroll = rememberScrollState()
    var titleHeight by remember { mutableIntStateOf(0) }
    val collapse by remember {
        derivedStateOf { if (titleHeight <= 0) 0f else (scroll.value / titleHeight.toFloat()).coerceIn(0f, 1f) }
    }
    val dock = rememberDockContraction(scroll)
    val motion = MaterialTheme.motionScheme
    val contracted = dock.contracted && !reducedMotion && !compact

    FloatingScaffold(
        topBar = {
            HomeHeader(
                title = deviceName,
                showTitle = collapse > HeaderSwapFraction,
                onSettings = onSettings,
            )
        },
        bottomBar = {
            if (!compact) {
                // Reserve the expanded dock height so the scroll padding stays put while labels hide.
                var dockHeight by remember { mutableIntStateOf(0) }
                Box(
                    Modifier
                        .floatingDockPadding()
                        .heightIn(min = with(LocalDensity.current) { dockHeight.toDp() }),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ModeDock(presentation, onMode, contracted = contracted, inline = false,
                        modifier = Modifier.onSizeChanged { dockHeight = maxOf(dockHeight, it.height) })
                }
            }
        },
        topEdgeVisible = { scroll.canScrollBackward },
        bottomEdgeVisible = { !compact && scroll.canScrollForward },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(dock.connection)
                    .verticalScroll(scroll)
                    .heightIn(min = viewport)
                    .padding(
                        start = ScreenGutter, end = ScreenGutter,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + ContentCardGap,
                    ),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    AnimatedContent(
                        deviceName,
                        modifier = Modifier
                            .onSizeChanged { titleHeight = it.height }
                            .graphicsLayer { alpha = (1f - collapse / TitleFadeFraction).coerceIn(0f, 1f) },
                        transitionSpec = { valueSwap(motion) },
                        label = "deviceName",
                    ) { name ->
                        Text(name, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    }
                    ConnectionStatus(state, canConnect, hasError, reducedMotion, Modifier.padding(top = 8.dp))
                    val actionLabel = stringResource(when {
                        state.status == "error" -> R.string.retry
                        canConnect -> R.string.connect
                        state.ready -> R.string.disconnect
                        else -> R.string.cancel
                    })
                    val actionModifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = MinTouchTarget)
                    AnimatedVisibility(!hasError, enter = contentEnter(), exit = contentExit()) {
                        // Filled Connect and outlined Disconnect/Cancel crossfade in place.
                        // The label travels with its button, and the outgoing button stops taking taps.
                        AnimatedContent(
                            canConnect to actionLabel,
                            transitionSpec = { valueSwap(motion) },
                            contentKey = { it.first },
                            label = "action",
                        ) { (connect, label) ->
                            val active = transition.targetState == EnterExitState.Visible
                            if (connect) {
                                Button(onClick = onConnect, enabled = active, modifier = actionModifier) { ActionLabel(label) }
                            } else {
                                OutlinedButton(onClick = onDisconnect, enabled = active, modifier = actionModifier) {
                                    ActionLabel(label)
                                }
                            }
                        }
                    }
                    AnimatedVisibility(hasError, enter = contentEnter(), exit = contentExit()) {
                        ConnectionErrorCard(state, onConnect, Modifier.padding(top = 20.dp))
                    }
                }
                ModeSign(presentation, state.ready, Modifier.fillMaxWidth().padding(vertical = 24.dp))
                Column {
                    AnimatedVisibility(needsSetup, enter = contentEnter(), exit = contentExit()) {
                        Column {
                            SectionLabel(stringResource(R.string.choose_endpoint), Modifier.padding(top = 0.dp))
                            SegmentedGroup {
                                state.endpoints.forEachIndexed { index, endpoint ->
                                    SegmentedRow(
                                        index, state.endpoints.size, endpoint.name,
                                        icon = Icons.Rounded.Bluetooth,
                                        supporting = endpoint.address,
                                        interaction = Modifier.clickable(role = Role.Button) { onEndpoint(endpoint.address) },
                                        trailing = {
                                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (compact) {
                        // Short windows or large text: the dock joins the content with a solid fill.
                        ModeDock(presentation, onMode, contracted = false, inline = true,
                            modifier = Modifier.padding(top = if (needsSetup) ContentCardGap else 0.dp))
                    }
                }
            }
        }
    }
}

/** Contracts on scroll down past the threshold; expands on scroll up and at either end. */
private class DockContraction {
    var contracted by mutableStateOf(false)
    var thresholdPx = 0f
    private var travel = 0f
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy != 0f) {
                travel = if ((travel < 0f) == (dy < 0f)) travel + dy else dy
                if (travel <= -thresholdPx) contracted = true
                if (travel >= thresholdPx) contracted = false
            }
            return Offset.Zero
        }
    }

    fun expand() {
        contracted = false
        travel = 0f
    }
}

@Composable
private fun rememberDockContraction(scroll: androidx.compose.foundation.ScrollState): DockContraction {
    val threshold = with(LocalDensity.current) { ScrollDirectionThreshold.toPx() }
    val dock = remember { DockContraction() }
    dock.thresholdPx = threshold
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value == 0 || scroll.value >= scroll.maxValue }
            .collect { atEnd -> if (atEnd) dock.expand() }
    }
    return dock
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeHeader(title: String, showTitle: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.floatingHeaderPadding(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FloatingHeaderGap),
    ) {
        Box(Modifier.weight(1f)) {
            GlassSurface {
                Box(
                    Modifier
                        .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                        .heightIn(min = FloatingHeaderHeight)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Crossfade(showTitle, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "headerLabel") { collapsed ->
                        if (collapsed) Text(title, style = MaterialTheme.typography.titleMedium)
                        else Text(stringResource(R.string.app_name), style = MoonModeBrandStyle)
                    }
                }
            }
        }
        GlassSurface {
            IconButton(onClick = onSettings, modifier = Modifier.size(FloatingHeaderHeight)) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
    }
}

/** Button text that crossfades when the action changes (Disconnect ↔ Cancel, Connect ↔ Retry). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionLabel(label: String) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(label, transitionSpec = { valueSwap(motion) }, label = "actionLabel") { Text(it) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionStatus(
    state: ClientState,
    canConnect: Boolean,
    hasError: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusDot(
            connecting = !reducedMotion && (state.status == "connecting" || state.status == "scanning"),
            disconnected = canConnect,
        )
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            if (hasError) stringResource(R.string.disconnected) else connectionStatus(state),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            transitionSpec = { valueSwap(motion) },
            label = "status",
        ) { text ->
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionStatusDot(connecting: Boolean, disconnected: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // The ring fills when connected and empties when disconnected.
    val filled by animateFloatAsState(
        if (disconnected) 0f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "dotFill",
    )
    val fill = color.copy(alpha = color.alpha * filled)
    val breathing = if (connecting) {
        val phase = rememberInfiniteTransition(label = "Connection breathing")
            .animateFloat(0f, 1f, StatusPulseSpec, label = "Status dot pulse")
        Modifier.graphicsLayer {
            alpha = 0.4f + 0.6f * phase.value
            scaleX = 0.85f + 0.3f * phase.value
            scaleY = scaleX
        }
    } else Modifier
    Box(
        Modifier.size(8.dp).then(breathing).border(1.dp, color, CircleShape).background(fill, CircleShape),
    )
}

/** Callout card: the first thing to notice when the connection fails. */
@Composable
private fun ConnectionErrorCard(state: ClientState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
    ) {
        Column(Modifier.padding(NoticeCardPadding)) {
            Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = MinTouchTarget),
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
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

/** Hero: the mode pictogram and its value. Cached readings are dimmed, never selected. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeSign(presentation: ModePresentation, ready: Boolean, modifier: Modifier = Modifier) {
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
    val motion = MaterialTheme.motionScheme
    // A cached reading dims; a live one brightens without swapping the pictogram.
    val emphasis by animateFloatAsState(
        if (presentation.isLive) 1f else DisabledContentAlpha, motion.defaultEffectsSpec(), label = "heroEmphasis",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            presentation.displayedMode to label,
            transitionSpec = { heroSwap(motion) },
            contentAlignment = Alignment.Center,
            label = "hero",
        ) { (mode, name) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(modeIcon(mode)),
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(120.dp).graphicsLayer { alpha = emphasis },
                )
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            }
        }
        AnimatedContent(caption, transitionSpec = { valueSwap(motion) }, label = "heroCaption") { text ->
            if (text != null) {
                Text(
                    text = stringResource(text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Glass dock with the three modes. The live mode is a secondary-container pill. Scrolling down
 * hides labels; [inline] is the solid in-flow state for short windows and large text.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeDock(
    presentation: ModePresentation,
    onMode: (AncMode) -> Unit,
    contracted: Boolean,
    inline: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val outerShape = if (inline) MaterialTheme.shapes.extraLarge else CircleShape
    val segmentShape = if (inline) MaterialTheme.shapes.large else CircleShape
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labelWidth = with(LocalDensity.current) {
            val track = maxWidth.roundToPx() - 2 * (DockTrackPadding + 1.dp).roundToPx() -
                2 * DockSegmentGap.roundToPx()
            (track / 3 - 2 * DockSegmentPadding.roundToPx()).coerceAtLeast(1)
        }
        // Fit full labels like the widget, keeping the three icons aligned.
        val labelSize = remember(textMeasurer, labelStyle, labelWidth) {
            (labelStyle.fontSize.value.toInt() downTo 8).firstOrNull { size ->
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
        GlassSurface(Modifier.fillMaxWidth(), shape = outerShape, blurred = !inline) {
            val selectedIndex = widgetModes().indexOf(presentation.liveMode).takeIf { it >= 0 }
            Box(Modifier.padding(DockTrackPadding).height(IntrinsicSize.Min)) {
                DockIndicator(selectedIndex, segmentShape, Modifier.matchParentSize())
                Row(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(DockSegmentGap),
                ) {
                    widgetModes().forEach { mode ->
                        ModeSegment(
                            mode = mode,
                            selected = presentation.liveMode == mode,
                            enabled = presentation.controlsEnabled,
                            dimmed = !presentation.controlsEnabled && !presentation.changing,
                            showLabel = !contracted,
                            labelStyle = labelStyle.copy(fontSize = labelSize.sp),
                            shape = segmentShape,
                            onClick = { onMode(mode) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The selection pill slides between segments on the spatial spec instead of each segment
 * recoloring. It appears in place when a live reading arrives and fades when the reading goes away.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DockIndicator(index: Int?, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier) {
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<Float>()
    val position = remember { Animatable((index ?: 0).toFloat()) }
    var placed by remember { mutableStateOf(index != null) }
    LaunchedEffect(index) {
        if (index == null) {
            placed = false
        } else {
            if (placed) position.animateTo(index.toFloat(), spatial) else position.snapTo(index.toFloat())
            placed = true
        }
    }
    val alpha by animateFloatAsState(if (index != null) 1f else 0f, motion.fastEffectsSpec(), label = "dockIndicator")
    val color = MaterialTheme.colorScheme.secondaryContainer
    // A layout modifier, not BoxWithConstraints: the track measures with intrinsic height.
    Box(
        modifier
            .layout { measurable, constraints ->
                // Intrinsic queries arrive unbounded; the pill then reports no size of its own.
                val trackWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val trackHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
                val gap = DockSegmentGap.roundToPx()
                val width = ((trackWidth - 2 * gap) / 3).coerceAtLeast(0)
                val pill = measurable.measure(Constraints.fixed(width, trackHeight))
                layout(trackWidth, trackHeight) {
                    // placeRelative mirrors the offset in right-to-left layouts.
                    pill.placeRelativeWithLayer(Math.round(position.value * (width + gap)), 0) { this.alpha = alpha }
                }
            }
            .background(color, shape),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeSegment(
    mode: AncMode,
    selected: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    showLabel: Boolean,
    labelStyle: androidx.compose.ui.text.TextStyle,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val selection by animateFloatAsState(if (selected) 1f else 0f, motion.fastEffectsSpec(), label = "segmentSelection")
    val content = androidx.compose.ui.graphics.lerp(colors.onSurface, colors.onSecondaryContainer, selection)
    val liveDescription = stringResource(R.string.live_selection)
    Surface(modifier = modifier, shape = shape, color = Color.Transparent, contentColor = content) {
        Column(
            Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics {
                    if (selected) stateDescription = liveDescription
                }
                .heightIn(min = MinTouchTarget + 8.dp)
                .padding(horizontal = DockSegmentPadding, vertical = 10.dp)
                .alpha(if (dimmed) DisabledContentAlpha else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(modeIcon(mode)), contentDescription = if (showLabel) null else mode.label,
                modifier = Modifier.size(28.dp))
            AnimatedVisibility(
                visible = showLabel,
                enter = fadeIn(motion.fastEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.defaultSpatialSpec()),
            ) {
                Text(
                    mode.label,
                    style = labelStyle,
                    textAlign = TextAlign.Center,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
