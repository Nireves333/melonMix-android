package me.magnum.melonds.ui.theme

import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import me.magnum.melonds.R

val uncheckedThumbColor: Color @Composable get() = colorResource(id = R.color.switchThumbUnselected)
val gameMasteryColor: Color get() = Color(0xFFFFD700)

// [KHMM] KH reskin: deep KH-menu navy primaries with a gold accent, replacing melonDS
// red/green. Mirrors values/colors.xml and values-night/colors.xml — keep them in sync.
val LightMelonColors @Composable get() = lightColors(
    primary = Color(0xFF1B2C5C),          // R.color.colorPrimary
    primaryVariant = Color(0xFF111C3D),   // R.color.colorPrimaryDark
    secondary = Color(0xFFC9A036),        // R.color.colorAccent
    secondaryVariant = Color(0xFFC9A036), // R.color.colorAccent
    background = Color(0xFFFFFFFF),       // R.color.colorBackground
    surface = Color(0xFFFAFAFA),          // R.color.colorSurface
    onPrimary = Color(0xFFFFFFFF),        // R.color.colorOnPrimary
    onSecondary = Color(0xFF231B06),      // dark text on the gold accent
    onSurface = Color(0xFF222222),        // R.color.textColorPrimary
    onBackground = Color(0xFF767676),     // R.color.textColorSecondary
)

val DarkMelonColors @Composable get() = darkColors(
    primary = Color(0xFF16204A),          // R.color.colorPrimary,
    primaryVariant = Color(0xFF0D1329),   // R.color.colorPrimaryDark,
    secondary = Color(0xFFE5B948),        // R.color.colorAccent,
    secondaryVariant = Color(0xFFE5B948), // R.color.colorAccent,
    background = Color(0xFF0A0E1A),       // R.color.colorBackground,
    surface = Color(0xFF151B2E),          // R.color.colorSurface,
    onPrimary = Color(0xFFFFFFFF),        // R.color.colorOnPrimary,
    onSecondary = Color(0xFF231B06),      // dark text on the gold accent,
    onSurface = Color(0xFFFFFFFF),        // R.color.textColorPrimary,
    onBackground = Color(0xFFB9C0D4),     // R.color.textColorSecondary,
)