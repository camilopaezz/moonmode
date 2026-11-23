package dev.camilo.st2mode.ui.theme

import dev.camilo.st2mode.SettingsPreferences
import dev.camilo.st2mode.ThemePreference
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Tonal-spot scheme generated from the MoonMode purple seed #5B4B8A.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF645789), onPrimary = Color(0xFFFDF7FF),
    primaryContainer = Color(0xFFD0C1FA), onPrimaryContainer = Color(0xFF463A6A),
    inversePrimary = Color(0xFFD0C1FA),
    secondary = Color(0xFF625C71), onSecondary = Color(0xFFFDF7FF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF554F64),
    tertiary = Color(0xFF7B5271), onTertiary = Color(0xFFFFF7F9),
    tertiaryContainer = Color(0xFFF9C5EA), onTertiaryContainer = Color(0xFF633C5A),
    background = Color(0xFFFDF8FE), onBackground = Color(0xFF34313A),
    surface = Color(0xFFFDF8FE), onSurface = Color(0xFF34313A),
    surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF615D68),
    surfaceTint = Color(0xFF645789),
    inverseSurface = Color(0xFF0F0D12), inverseOnSurface = Color(0xFF9F9BA1),
    error = Color(0xFFA8364B), onError = Color(0xFFFFF7F7),
    errorContainer = Color(0xFFF97386), onErrorContainer = Color(0xFF6E0523),
    outline = Color(0xFF7D7984), outlineVariant = Color(0xFFB5B0BB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFDF8FE), surfaceDim = Color(0xFFDED8E4),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F2FA),
    surfaceContainer = Color(0xFFF2ECF5), surfaceContainerHigh = Color(0xFFECE6F1),
    surfaceContainerHighest = Color(0xFFE7E0EC),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFCDC0EC), onPrimary = Color(0xFF443B5F),
    primaryContainer = Color(0xFF564D72), onPrimaryContainer = Color(0xFFE9DEFF),
    inversePrimary = Color(0xFF635980),
    secondary = Color(0xFFCBC3DC), onSecondary = Color(0xFF433D51),
    secondaryContainer = Color(0xFF3E384C), onSecondaryContainer = Color(0xFFC4BBD4),
    tertiary = Color(0xFFFFD7F2), onTertiary = Color(0xFF6C4563),
    tertiaryContainer = Color(0xFFF9C5EA), onTertiaryContainer = Color(0xFF633C5A),
    background = Color(0xFF0F0D12), onBackground = Color(0xFFE9E3EF),
    surface = Color(0xFF0F0D12), onSurface = Color(0xFFE9E3EF),
    surfaceVariant = Color(0xFF27242D), onSurfaceVariant = Color(0xFFAEA9B4),
    surfaceTint = Color(0xFFCDC0EC),
    inverseSurface = Color(0xFFFDF8FE), inverseOnSurface = Color(0xFF575459),
    error = Color(0xFFF97386), onError = Color(0xFF490013),
    errorContainer = Color(0xFF871C34), onErrorContainer = Color(0xFFFF97A3),
    outline = Color(0xFF78747E), outlineVariant = Color(0xFF4A4650),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2D2B34), surfaceDim = Color(0xFF0F0D12),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF141218),
    surfaceContainer = Color(0xFF1B191F), surfaceContainerHigh = Color(0xFF211E26),
    surfaceContainerHighest = Color(0xFF27242D),
)

internal val MoonModeShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun St2ModeTheme(settings: SettingsPreferences = SettingsPreferences(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val scheme = if (settings.wallpaperColors) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme(scheme),
        typography = MoonModeTypography,
        shapes = MoonModeShapes,
        content = content,
    )
}

/**
 * Switching theme or wallpaper colors recolors the whole app over the motion scheme's slow
 * effects spec instead of cutting. Dynamic schemes are rebuilt each composition, so a change is
 * detected by comparing roles rather than instances.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val latest by rememberUpdatedState(target)
    var settled by remember { mutableStateOf(target) }
    var start by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = remember { MotionScheme.expressive().slowEffectsSpec<Float>() }
    LaunchedEffect(target.primary, target.surface, target.onSurface, target.secondaryContainer) {
        if (progress.value >= 1f && sameRoles(settled, latest)) return@LaunchedEffect
        start = lerp(start, settled, progress.value) // what is on screen now, even mid-animation
        settled = latest
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    // Until the effect starts a change, keep showing the settled scheme rather than cutting to the new one.
    return when {
        progress.value < 1f -> lerp(start, settled, progress.value)
        sameRoles(settled, latest) -> latest
        else -> settled
    }
}

private fun sameRoles(a: ColorScheme, b: ColorScheme) =
    a.primary == b.primary && a.surface == b.surface && a.onSurface == b.onSurface &&
        a.secondaryContainer == b.secondaryContainer

private fun lerp(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun Color.to(other: Color) = lerp(this, other, t)
    return b.copy(
        primary = a.primary.to(b.primary),
        onPrimary = a.onPrimary.to(b.onPrimary),
        primaryContainer = a.primaryContainer.to(b.primaryContainer),
        onPrimaryContainer = a.onPrimaryContainer.to(b.onPrimaryContainer),
        inversePrimary = a.inversePrimary.to(b.inversePrimary),
        secondary = a.secondary.to(b.secondary),
        onSecondary = a.onSecondary.to(b.onSecondary),
        secondaryContainer = a.secondaryContainer.to(b.secondaryContainer),
        onSecondaryContainer = a.onSecondaryContainer.to(b.onSecondaryContainer),
        tertiary = a.tertiary.to(b.tertiary),
        onTertiary = a.onTertiary.to(b.onTertiary),
        tertiaryContainer = a.tertiaryContainer.to(b.tertiaryContainer),
        onTertiaryContainer = a.onTertiaryContainer.to(b.onTertiaryContainer),
        background = a.background.to(b.background),
        onBackground = a.onBackground.to(b.onBackground),
        surface = a.surface.to(b.surface),
        onSurface = a.onSurface.to(b.onSurface),
        surfaceVariant = a.surfaceVariant.to(b.surfaceVariant),
        onSurfaceVariant = a.onSurfaceVariant.to(b.onSurfaceVariant),
        surfaceTint = a.surfaceTint.to(b.surfaceTint),
        inverseSurface = a.inverseSurface.to(b.inverseSurface),
        inverseOnSurface = a.inverseOnSurface.to(b.inverseOnSurface),
        error = a.error.to(b.error),
        onError = a.onError.to(b.onError),
        errorContainer = a.errorContainer.to(b.errorContainer),
        onErrorContainer = a.onErrorContainer.to(b.onErrorContainer),
        outline = a.outline.to(b.outline),
        outlineVariant = a.outlineVariant.to(b.outlineVariant),
        scrim = a.scrim.to(b.scrim),
        surfaceBright = a.surfaceBright.to(b.surfaceBright),
        surfaceDim = a.surfaceDim.to(b.surfaceDim),
        surfaceContainer = a.surfaceContainer.to(b.surfaceContainer),
        surfaceContainerHigh = a.surfaceContainerHigh.to(b.surfaceContainerHigh),
        surfaceContainerHighest = a.surfaceContainerHighest.to(b.surfaceContainerHighest),
        surfaceContainerLow = a.surfaceContainerLow.to(b.surfaceContainerLow),
        surfaceContainerLowest = a.surfaceContainerLowest.to(b.surfaceContainerLowest),
    )
}
