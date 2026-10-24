package cn.edu.cppu.libraryautobooker

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F2D4),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF68548A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDDCFF),
    onSecondaryContainer = Color(0xFF241046),
    tertiary = Color(0xFF875500),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F6F9),
    onBackground = Color(0xFF25202D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF25202D),
    surfaceVariant = Color(0xFFF3ECF8),
    onSurfaceVariant = Color(0xFF5B5560),
    surfaceDim = Color(0xFFE4E0E7),
    surfaceBright = Color(0xFFFDF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAFD),
    surfaceContainer = Color(0xFFF3F1F6),
    surfaceContainerHigh = Color(0xFFEDE9F1),
    surfaceContainerHighest = Color(0xFFE7E2EA),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCBC4CF),
    inverseSurface = Color(0xFF332F36),
    inverseOnSurface = Color(0xFFF6EFF7),
    inversePrimary = Color(0xFF8BD6B9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6B9),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFFA9F2D4),
    secondary = Color(0xFFD5BCF7),
    onSecondary = Color(0xFF37265A),
    secondaryContainer = Color(0xFF4F3C72),
    onSecondaryContainer = Color(0xFFEDDCFF),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF663C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE8E1E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE8E1E9),
    surfaceVariant = Color(0xFF4A4453),
    onSurfaceVariant = Color(0xFFCBC4CF),
    surfaceDim = Color(0xFF141218),
    surfaceBright = Color(0xFF3A3840),
    surfaceContainerLowest = Color(0xFF0E0C12),
    surfaceContainerLow = Color(0xFF1B1920),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF958F99),
    outlineVariant = Color(0xFF4A4453),
    inverseSurface = Color(0xFFE8E1E9),
    inverseOnSurface = Color(0xFF332F36),
    inversePrimary = Color(0xFF176B50)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val BaseTypography = Typography()

private val AppTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Medium)
)

@Composable
fun LibraryAutoBookerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
