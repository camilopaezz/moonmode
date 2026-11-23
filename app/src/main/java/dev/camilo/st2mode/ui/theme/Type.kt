package dev.camilo.st2mode.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.camilo.st2mode.R

private val MoonModeFont = FontFamily(
    Font(R.font.adwaita_sans_regular, FontWeight.Normal),
    Font(R.font.adwaita_sans_medium, FontWeight.Medium),
)

private val defaults = Typography()

/** Override: the wordmark keeps the Doto dot-matrix face at the system's 22/28 sp wordmark size. */
val MoonModeBrandStyle = TextStyle(
    fontFamily = FontFamily(Font(R.font.doto_extra_bold, FontWeight.ExtraBold)),
    fontWeight = FontWeight.ExtraBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp,
)

val MoonModeTypography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = MoonModeFont),
    displayMedium = defaults.displayMedium.copy(fontFamily = MoonModeFont),
    displaySmall = defaults.displaySmall.copy(
        fontFamily = MoonModeFont, fontWeight = FontWeight.Medium,
        fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp,
    ),
    headlineLarge = defaults.headlineLarge.copy(
        fontFamily = MoonModeFont, fontWeight = FontWeight.Medium,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp,
    ),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = MoonModeFont, fontWeight = FontWeight.Medium),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = MoonModeFont, fontWeight = FontWeight.Medium),
    titleLarge = defaults.titleLarge.copy(fontFamily = MoonModeFont, fontWeight = FontWeight.Medium),
    titleMedium = defaults.titleMedium.copy(fontFamily = MoonModeFont, fontWeight = FontWeight.Medium),
    titleSmall = defaults.titleSmall.copy(fontFamily = MoonModeFont, fontWeight = FontWeight.Medium),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    bodySmall = defaults.bodySmall.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    labelLarge = defaults.labelLarge.copy(fontFamily = MoonModeFont, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = defaults.labelMedium.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    labelSmall = defaults.labelSmall.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
)
