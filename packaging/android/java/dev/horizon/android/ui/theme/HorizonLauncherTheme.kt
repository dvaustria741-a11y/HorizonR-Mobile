package dev.horizon.android.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.horizon.android.model.LauncherThemePalette

// Inter everywhere, sized for a dense desktop-style launcher (13 sp body, 12 sp labels).
val LauncherTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
).withFamily(HzFont.Sans)

@Composable
fun HorizonLauncherTheme(
    palette: LauncherThemePalette = LauncherThemePalette.EMBERMIRE,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        val primary = Color(palette.primaryHex)
        val secondary = Color(palette.secondaryHex)
        val surface = Color(palette.surfaceHex)
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF12141A),
            primaryContainer = primary.copy(alpha = 0.22f),
            onPrimaryContainer = Color(0xFFFFEDE6),
            secondary = secondary,
            onSecondary = Color(0xFF12141A),
            secondaryContainer = secondary.copy(alpha = 0.20f),
            onSecondaryContainer = Color(0xFFFFF3E0),
            background = surface,
            onBackground = Color(0xFFECEFF4),
            surface = surface,
            onSurface = Color(0xFFECEFF4),
            surfaceVariant = Color(0xFF1E2433),
            onSurfaceVariant = Color(0xFFB8C2D4),
            outline = Color(0xFF333D52)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LauncherTypography,
        content = {
            // Text drawn outside a Surface would otherwise fall back to the system font.
            androidx.compose.material3.ProvideTextStyle(LauncherTypography.bodyMedium, content)
        }
    )
}
