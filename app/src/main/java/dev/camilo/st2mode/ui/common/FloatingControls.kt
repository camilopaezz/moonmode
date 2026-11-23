package dev.camilo.st2mode.ui.common

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// Layout tokens (android-glass-ui TOKENS.md).
internal val ScreenGutter = 20.dp
internal val FloatingHeaderHeight = 52.dp
internal val FloatingHeaderGap = 10.dp
internal val DockOuterSpacing = 16.dp
internal val ScrollEdgeExtra = 20.dp
internal val ContentCardGap = 12.dp
internal val ContentCardPadding = 16.dp
internal val NoticeCardPadding = 24.dp
internal val SegmentGap = 2.dp
internal val SegmentGroupGap = 24.dp
internal val MinTouchTarget = 48.dp

/** Windows shorter than this, or with less effective height at the current font scale, place controls inline. */
private const val CompactWindowHeightDp = 480
private const val CompactEffectiveHeightDp = 360

/** Contraction trigger for docks while scrolling. */
internal val ScrollDirectionThreshold = 10.dp

/** Material disabled-content emphasis. */
internal const val DisabledContentAlpha = 0.38f

/** Override: looping status indicator; MotionScheme has no repeating spec. */
internal val StatusPulseSpec: InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse)

private val GlassBlur = 20.dp
private const val GlassTintAlpha = .60f
private const val GlassFallbackTintAlpha = .94f
private const val RimHighlightLight = .85f
private const val RimHighlightDark = .55f
private const val RimHighlightLerp = .55f
private const val RimShadeAlpha = .25f

private val LocalBackdrop = compositionLocalOf<HazeState?> { null }
private val LocalFloatingInsets = compositionLocalOf<WindowInsets> { WindowInsets(0, 0, 0, 0) }

@Composable
internal fun Modifier.floatingHeaderPadding(): Modifier =
    fillMaxWidth()
        .windowInsetsPadding(LocalFloatingInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(start = ScreenGutter, top = 16.dp, end = ScreenGutter, bottom = 4.dp)

@Composable
internal fun Modifier.floatingDockPadding(): Modifier =
    fillMaxWidth()
        .windowInsetsPadding(LocalFloatingInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(start = ScreenGutter, end = ScreenGutter, top = 12.dp, bottom = DockOuterSpacing)

/** True when floating controls should drop into the content flow with a solid fill. */
@Composable
internal fun rememberCompactWindow(): Boolean {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    return configuration.screenHeightDp < CompactWindowHeightDp ||
        configuration.screenHeightDp / fontScale < CompactEffectiveHeightDp
}

/** Touch exploration or disabled animations keep labels and indicators static. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(reducedMotion(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { reduced = reducedMotion(context) }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return reduced
}

private fun reducedMotion(context: Context): Boolean {
    val touchExploration = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return touchExploration || scale == 0f
}

/**
 * The content slot is the only blur source. Controls and edge bands are drawn as its siblings,
 * so nothing blurs itself. Content receives scrollable padding that clears the header and dock.
 */
@Composable
internal fun FloatingScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    backdrop: HazeState = rememberHazeState(),
    topEdgeVisible: () -> Boolean = { true },
    bottomEdgeVisible: () -> Boolean = { false },
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val statusHeight = contentWindowInsets.asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalFloatingInsets provides contentWindowInsets) {
        Scaffold(
            topBar = topBar,
            bottomBar = bottomBar,
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = contentWindowInsets,
        ) { padding ->
            val headerHeight = (padding.calculateTopPadding() - statusHeight).coerceAtLeast(0.dp)
            // System status icons sit over a clear inset; only the app controls overlap the content.
            Box(Modifier.fillMaxSize().windowInsetsPadding(
                contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )) {
                Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                    content(PaddingValues(top = headerHeight, bottom = padding.calculateBottomPadding()))
                }
                ScrollEdgeBand(backdrop, top = true, visible = topEdgeVisible(),
                    height = headerHeight + ScrollEdgeExtra, modifier = Modifier.align(Alignment.TopCenter))
                ScrollEdgeBand(backdrop, top = false, visible = bottomEdgeVisible(),
                    height = padding.calculateBottomPadding() + ScrollEdgeExtra,
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** A uniform blur faded by a mask to the outer half, under a surface scrim. Passes touches through. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScrollEdgeBand(backdrop: HazeState, top: Boolean, visible: Boolean, height: Dp, modifier: Modifier) {
    val alpha by animateFloatAsState(
        if (visible) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "scrollEdge",
    )
    if (alpha <= 0f) return
    val surface = MaterialTheme.colorScheme.surface
    val scrim = Brush.verticalGradient(*if (top) arrayOf(
        0f to surface.copy(alpha = alpha), .35f to surface.copy(alpha = .6f * alpha), 1f to Color.Transparent,
    ) else arrayOf(
        0f to Color.Transparent, .65f to surface.copy(alpha = .6f * alpha), 1f to surface.copy(alpha = alpha),
    ))
    // minSdk is 31, so backdrop blur is always available. A mask fades a uniform blur:
    // progressive blur shaders crash software-rendered GPUs on CI emulators.
    val blur = Modifier.hazeEffect(backdrop, HazeStyle(
        backgroundColor = surface, tint = HazeTint(Color.Transparent), blurRadius = GlassBlur, noiseFactor = 0f,
    )) {
        this.alpha = alpha
        mask = Brush.verticalGradient(*if (top) arrayOf(0f to Color.Black, .5f to Color.Transparent)
            else arrayOf(.5f to Color.Transparent, 1f to Color.Black))
    }
    Box(modifier.fillMaxWidth().height(height).clip(RectangleShape).then(blur).background(scrim))
}

/**
 * The single glass surface for every control. Blurs only the content layer behind it and carries
 * a lit rim instead of a shadow. [blurred] = false is the solid inline state.
 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    blurred: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val source = if (blurred) LocalBackdrop.current else null
    val rim = remember(colors.surface, colors.surfaceContainerHigh, colors.outlineVariant) {
        val highlight = if (colors.surface.luminance() < .5f) RimHighlightDark else RimHighlightLight
        Brush.verticalGradient(listOf(
            lerp(colors.surfaceContainerHigh, Color.White, RimHighlightLerp).copy(alpha = highlight),
            Color.Transparent,
            colors.outlineVariant.copy(alpha = RimShadeAlpha),
        ))
    }
    val effect = if (source != null) Modifier.clip(shape).hazeEffect(source, HazeStyle(
        backgroundColor = colors.surface,
        tint = HazeTint(colors.surfaceContainerHigh.copy(alpha = GlassTintAlpha)),
        blurRadius = GlassBlur,
        noiseFactor = 0f,
        fallbackTint = HazeTint(colors.surfaceContainerHigh.copy(alpha = GlassFallbackTintAlpha)),
    )) else Modifier
    Surface(
        modifier = modifier.then(effect),
        shape = shape,
        color = if (source != null) Color.Transparent else colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, rim),
        content = content,
    )
}

/** Split header: a 52 dp back circle beside a title capsule that grows for wrapped text. */
@Composable
internal fun FloatingScreenHeader(title: String, backLabel: String, onBack: () -> Unit) {
    Row(
        Modifier.floatingHeaderPadding(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FloatingHeaderGap),
    ) {
        GlassSurface {
            IconButton(onClick = onBack, modifier = Modifier.size(FloatingHeaderHeight)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, backLabel)
            }
        }
        GlassSurface(Modifier.weight(1f, fill = false)) {
            Box(
                Modifier.heightIn(min = FloatingHeaderHeight).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
