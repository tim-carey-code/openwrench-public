package org.openwrench.frontend

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Offline system fonts and native Compose surfaces, shared by desktop and Android. */
internal val TelemetryInk = Color(0xFF49D2E5)
internal val DashboardBlue = Color(0xFF4089FF)
internal val DashboardCanvas = Color(0xFF0C131D)
internal val DashboardNavigation = Color(0xFF0F1A26)
internal val DashboardPanel = Color(0xFF16212D)
internal val DashboardBorder = Color(0xFF2D4053)
internal val DashboardMuted = Color(0xFF8298B2)
internal val DashboardGreen = Color(0xFF34D399)
internal val DashboardAmber = Color(0xFFF5B74F)

internal fun diagnosticColors() = darkColorScheme(
    primary = DashboardBlue, onPrimary = Color(0xFF0A1839),
    primaryContainer = Color(0xFF1D3858), onPrimaryContainer = Color(0xFFF3F6FA),
    secondary = TelemetryInk, onSecondary = Color(0xFF06362C),
    secondaryContainer = Color(0xFF1D3858), onSecondaryContainer = Color(0xFFB9F1FA),
    background = DashboardCanvas, onBackground = Color(0xFFF3F6FA),
    surface = DashboardPanel, onSurface = Color(0xFFF3F6FA),
    surfaceVariant = Color(0xFF1C2B3C), onSurfaceVariant = Color(0xFFA6B7CB),
    outline = Color(0xFF66788E), outlineVariant = DashboardBorder,
    tertiary = DashboardAmber, error = Color(0xFFFF6B6B),
)

@Composable
internal fun DiagnosticTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = diagnosticColors(),
    typography = Typography(
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    ),
    shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp)),
    content = { CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 12.sp)) { content() } },
)

internal val TelemetryNumber = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
