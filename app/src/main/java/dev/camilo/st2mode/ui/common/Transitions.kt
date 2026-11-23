package dev.camilo.st2mode.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.activity.BackEventCompat

/** Screen change: a shared X axis. The incoming screen travels this fraction of the width. */
private const val ScreenSlideFraction = 8
/** The outgoing screen recedes slightly, like the system's predictive back preview. */
private const val ScreenRecedeScale = .94f
/** Predictive back preview (Material in-app back): the screen shrinks and drifts from the swiped edge. */
private const val BackPreviewScale = .1f
private const val BackPreviewShiftFraction = 20
private val BackPreviewMargin = 8.dp
/** A swapped value (status text, mode name) rises this fraction of its own height. */
private const val ValueRiseFraction = 3
/** The hero pictogram grows from this scale when the mode changes. */
private const val HeroEnterScale = .85f

/**
 * Home ↔ Settings. [forward] opens a deeper screen; backward is also what predictive back seeks
 * through, so the outgoing screen recedes as the gesture progresses.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun screenTransform(motion: MotionScheme, forward: Boolean): ContentTransform {
    val enter = slideInHorizontally(motion.defaultSpatialSpec()) { width ->
        if (forward) width / ScreenSlideFraction else -width / ScreenSlideFraction
    } + fadeIn(motion.defaultEffectsSpec())
    val exit = if (forward) {
        slideOutHorizontally(motion.defaultSpatialSpec()) { -it / ScreenSlideFraction } +
            fadeOut(motion.fastEffectsSpec())
    } else {
        slideOutHorizontally(motion.defaultSpatialSpec()) { it / ScreenSlideFraction } +
            scaleOut(motion.defaultSpatialSpec(), targetScale = ScreenRecedeScale) +
            fadeOut(motion.fastEffectsSpec())
    }
    return (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else 0f }
}

/** A changed label or value: the new one rises in while the old one fades, and the box resizes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun <T> AnimatedContentTransitionScope<T>.valueSwap(motion: MotionScheme): ContentTransform =
    (slideInVertically(motion.defaultSpatialSpec()) { it / ValueRiseFraction } + fadeIn(motion.defaultEffectsSpec()))
        .togetherWith(fadeOut(motion.fastEffectsSpec()))
        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })

/** The hero pictogram: the new mode grows in as the old one fades out. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun <T> AnimatedContentTransitionScope<T>.heroSwap(motion: MotionScheme): ContentTransform =
    (scaleIn(motion.defaultSpatialSpec(), initialScale = HeroEnterScale) + fadeIn(motion.defaultEffectsSpec()))
        .togetherWith(fadeOut(motion.fastEffectsSpec()))
        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })

/** Content that appears in the flow (callouts, setup groups): expands its space, then fades in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun contentEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec())
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun contentExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
}

/**
 * Material's in-app predictive back preview, driven by gesture [progress] (0–1): the screen
 * scales to 90 %, moves away from the [edge] the swipe started on, and rounds its corners.
 */
internal fun Modifier.predictiveBackPreview(progress: () -> Float, edge: Int): Modifier = graphicsLayer {
    val p = progress()
    if (p <= 0f) return@graphicsLayer
    val scale = 1f - BackPreviewScale * p
    scaleX = scale
    scaleY = scale
    val shift = (size.width / BackPreviewShiftFraction - BackPreviewMargin.toPx()) * p
    translationX = if (edge == BackEventCompat.EDGE_RIGHT) -shift else shift
    shape = RoundedCornerShape(BackPreviewCorner * p)
    clip = true
}

private val BackPreviewCorner = 28.dp
