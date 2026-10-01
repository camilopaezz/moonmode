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

val MoonModeBrandStyle = TextStyle(
    fontFamily = FontFamily(Font(R.font.doto_extra_bold, FontWeight.ExtraBold)),
    fontWeight = FontWeight.ExtraBold,
    fontSize = 28.sp,
    lineHeight = 36.sp,
    letterSpacing = 0.sp,
)

val MoonModeTypography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = MoonModeFont),
    displayMedium = defaults.displayMedium.copy(fontFamily = MoonModeFont),
    displaySmall = defaults.displaySmall.copy(fontFamily = MoonModeFont),
    headlineLarge = defaults.headlineLarge.copy(
        fontFamily = MoonModeFont,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 33.sp,
        letterSpacing = (-1.65).sp,
    ),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = MoonModeFont),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = MoonModeFont),
    titleLarge = defaults.titleLarge.copy(fontFamily = MoonModeFont),
    titleMedium = defaults.titleMedium.copy(fontFamily = MoonModeFont),
    titleSmall = defaults.titleSmall.copy(fontFamily = MoonModeFont, letterSpacing = (-0.3).sp),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = MoonModeFont),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = MoonModeFont),
    bodySmall = defaults.bodySmall.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    labelLarge = defaults.labelLarge.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    labelMedium = defaults.labelMedium.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
    labelSmall = defaults.labelSmall.copy(fontFamily = MoonModeFont, letterSpacing = 0.sp),
)
