package io.github.kindleshard.elite8active.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Brand colours, taken from the launcher icon: navy body, amber earbuds. */
val Navy = Color(0xFF1C2541)
val Amber = Color(0xFFFFC83D)

/** Status colours that read on both themes: good (battery, connected) and getting low. */
val Good = Color(0xFF4CD08A)
val Low = Color(0xFFFF9F43)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Navy,
    primaryContainer = Color(0xFF4A3B0C),
    onPrimaryContainer = Color(0xFFFFE6A6),
    secondary = Color(0xFFA9BCF0),
    onSecondary = Color(0xFF15204A),
    secondaryContainer = Color(0xFF2A3560),
    onSecondaryContainer = Color(0xFFDCE3FF),
    background = Color(0xFF0D1220),
    onBackground = Color(0xFFE5E8F2),
    surface = Color(0xFF0D1220),
    onSurface = Color(0xFFE5E8F2),
    surfaceVariant = Color(0xFF2B3248),
    onSurfaceVariant = Color(0xFFB4BBD0),
    surfaceContainerLowest = Color(0xFF080C17),
    surfaceContainerLow = Color(0xFF121829),
    surfaceContainer = Color(0xFF161D31),
    surfaceContainerHigh = Color(0xFF1F273E),
    surfaceContainerHighest = Color(0xFF29324B),
    outline = Color(0xFF59617A),
    outlineVariant = Color(0xFF353D55),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7D5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF96),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Navy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F7),
    onSecondaryContainer = Color(0xFF141C34),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF181C25),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF181C25),
    surfaceVariant = Color(0xFFE1E4EE),
    onSurfaceVariant = Color(0xFF4A5063),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F2F8),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEBEDF5),
    surfaceContainerHighest = Color(0xFFE3E6F0),
    outline = Color(0xFF7A8094),
    outlineVariant = Color(0xFFD2D6E2),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().let {
    it.copy(
        headlineSmall = it.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = it.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun EliteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
