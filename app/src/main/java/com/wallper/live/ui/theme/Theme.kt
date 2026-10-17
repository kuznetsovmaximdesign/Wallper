package com.wallper.live.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wallper is a dark, content-first app: the footage provides the colour. */
private val WallperColors = darkColorScheme(
    primary = Color(0xFFF2F0FF),
    onPrimary = Color(0xFF0B0A14),
    primaryContainer = Color(0xFF2A2840),
    onPrimaryContainer = Color(0xFFE7E3FF),
    secondary = Color(0xFFB9B4D6),
    onSecondary = Color(0xFF15131F),
    secondaryContainer = Color(0xFF24222F),
    onSecondaryContainer = Color(0xFFE6E2F5),
    tertiary = Color(0xFFFFB98F),
    onTertiary = Color(0xFF2B1407),
    background = Color(0xFF07070A),
    onBackground = Color(0xFFF4F4F7),
    surface = Color(0xFF07070A),
    onSurface = Color(0xFFF4F4F7),
    surfaceVariant = Color(0xFF1B1B22),
    onSurfaceVariant = Color(0xFFA6A6B4),
    surfaceContainerLowest = Color(0xFF050507),
    surfaceContainerLow = Color(0xFF0D0D12),
    surfaceContainer = Color(0xFF121218),
    surfaceContainerHigh = Color(0xFF191920),
    surfaceContainerHighest = Color(0xFF212129),
    outline = Color(0xFF34343F),
    outlineVariant = Color(0xFF24242D),
    error = Color(0xFFFF8A80),
)

private val base = Typography()

private val WallperTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val WallperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Material 3 Expressive: springy motion scheme, shape-morphing components. */
@Composable
fun WallperTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = WallperColors,
        motionScheme = MotionScheme.expressive(),
        typography = WallperTypography,
        shapes = WallperShapes,
        content = content,
    )
}
