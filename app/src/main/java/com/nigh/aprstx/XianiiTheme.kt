package com.nigh.aprstx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Hex from @xianii/design-system tokens.css (oklch → sRGB), see
// https://github.com/Nigh/xianii-theme packages/design-system/docs/preview.svg

private val DarkPrimary = Color(0xFFFFA1AD)
private val DarkOnPrimary = Color(0xFF242424)
private val DarkSecondary = Color(0xFFD8B0FF)
private val DarkOnSecondary = Color(0xFF242424)
private val DarkTertiary = Color(0xFF7FCFC4)
private val DarkOnTertiary = Color(0xFF242424)
private val DarkError = Color(0xFFFA6863)
private val DarkOnError = Color(0xFF1E1311)
private val DarkBackground = Color(0xFF161616) // base-200
private val DarkSurface = Color(0xFF242424) // base-100
private val DarkSurfaceVariant = Color(0xFF404040) // base-300
private val DarkOnSurface = Color(0xFFF2F2F2) // base-content
private val DarkOutline = Color(0xFF404040)
private val DarkNeutral = Color(0xFF44403C)

private val LightPrimary = Color(0xFFB3485C)
private val LightOnPrimary = Color(0xFFF8F8F8)
private val LightSecondary = Color(0xFF8658B1)
private val LightOnSecondary = Color(0xFFF8F8F8)
private val LightTertiary = Color(0xFF087970)
private val LightOnTertiary = Color(0xFFF8F8F8)
private val LightError = Color(0xFFB71824)
private val LightOnError = Color(0xFFFFF6F5)
private val LightBackground = Color(0xFFE8E8E8) // base-200
private val LightSurface = Color(0xFFF5F5F5) // base-100
private val LightSurfaceVariant = Color(0xFFD4D4D4) // base-300
private val LightOnSurface = Color(0xFF161616) // base-content
private val LightOutline = Color(0xFFD4D4D4)
private val LightNeutral = Color(0xFF1D140D)

val XianiiSuccess = Color(0xFF7FC08C)
val XianiiWarning = Color(0xFFEEBC4A)
val XianiiError = Color(0xFFFA6863)
val XianiiInfo = Color(0xFF7CB3EB)

val XianiiSuccessLight = Color(0xFF337344)
val XianiiWarningLight = Color(0xFFA07100)
val XianiiErrorLight = Color(0xFFB71824)
val XianiiInfoLight = Color(0xFF32669A)

private val XianiiDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    error = DarkError,
    onError = DarkOnError,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkBackground,
    inversePrimary = LightPrimary,
    scrim = Color.Black.copy(alpha = 0.5f),
    surfaceContainerHighest = DarkNeutral,
)

private val XianiiLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    error = LightError,
    onError = LightOnError,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurface,
    outline = LightOutline,
    outlineVariant = LightOutline,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightSurface,
    inversePrimary = DarkPrimary,
    scrim = Color.Black.copy(alpha = 0.5f),
    surfaceContainerHighest = LightNeutral,
)

// tokens: --radius-selector/field/box = 0.5rem ≈ 8dp
private val XianiiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun XianiiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) XianiiDarkColors else XianiiLightColors,
        shapes = XianiiShapes,
        content = content,
    )
}
