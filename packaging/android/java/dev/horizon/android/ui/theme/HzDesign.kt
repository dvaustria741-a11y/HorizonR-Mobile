package dev.horizon.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.horizon.android.R

/**
 * Typefaces bundled in res/font (both SIL Open Font License, see THIRD_PARTY_NOTICES.md).
 * Inter for everything you read, JetBrains Mono for numbers, versions, logs and field values.
 */
object HzFont {
    val Sans = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
        Font(R.font.inter_bold, FontWeight.ExtraBold)
    )
    val Mono = FontFamily(
        Font(R.font.jbmono_regular, FontWeight.Normal),
        Font(R.font.jbmono_regular, FontWeight.Medium),
        Font(R.font.jbmono_bold, FontWeight.SemiBold),
        Font(R.font.jbmono_bold, FontWeight.Bold)
    )
}

/**
 * One motion language for launcher and editor: things arrive fast and settle softly, they leave faster
 * than they came. Only alpha / translation / scale are animated (all graphicsLayer, so no relayout).
 */
object HzMotion {
    /** Fast start, long soft landing. Use for anything that enters. */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Slow start. Use for anything that leaves. */
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    const val Quick = 110
    const val Short = 170
    const val Medium = 240
}

/** Surfaces for the desktop-style shell. Flat fills and a single hairline, no gradients. */
object HzColor {
    val Rail = Color(0xFF0E1117)
    val Panel = Color(0xFF151921)
    val PanelHi = Color(0xFF1E232D)
    val Hairline = Color(0x1FFFFFFF)
    val Glass = Color(0xD0111419)
    val Danger = Color(0xFFFF8A80)
}

/** Small radii on purpose: a desktop launcher, not a toy. */
object HzShape {
    val Panel = RoundedCornerShape(8.dp)
    val Control = RoundedCornerShape(6.dp)
    val Chip = RoundedCornerShape(4.dp)
}

/** Same type scale, every style set in [family]. */
internal fun Typography.withFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family)
)

/** Typography for the in-game control menu and the layout editor (they live outside the launcher theme). */
val ControlsTypography: Typography get() = LauncherTypography
