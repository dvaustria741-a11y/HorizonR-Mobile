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
 * Manrope for everything you read, JetBrains Mono for numbers, versions, logs and field values.
 */
object HzFont {
    val Sans = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
        Font(R.font.manrope_extrabold, FontWeight.ExtraBold)
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

/**
 * Default theme tokens: graphite surfaces with a cool tint, soft cyan accent. Every launcher and editor
 * colour that is not a per-theme palette colour comes from here, so a new theme only has to change this block.
 */
object HzColor {
    /** Page background behind everything. */
    val Background = Color(0xFF0F1216)

    /** Top bar, side rails. */
    val Rail = Color(0xFF0C0F13)

    /** Cards, dialogs, side panels. */
    val Panel = Color(0xFF14181E)

    /** Raised or selected surfaces inside a Panel: rows, chips, fields. */
    val PanelHi = Color(0xFF1C2128)

    /** Recessed wells: log boxes, text fields, anything that sits inside a Panel. */
    val PanelLow = Color(0xFF0E1115)

    /** Panels drawn over wallpaper artwork. */
    val Glass = Color(0xD00F1217)

    val Hairline = Color(0x1FFFFFFF)

    /** Hairline for focused or selected outlines and for dividers on bright surfaces. */
    val HairlineStrong = Color(0x38FFFFFF)

    val TextPrimary = Color(0xFFE9EDF1)
    val TextIcon = Color(0xFFC3CAD2)
    val TextMuted = Color(0xFF8A94A0)

    val Accent = Color(0xFF5EC4D4)
    val AccentSecondary = Color(0xFF8EA4C4)
    val OnAccent = Color(0xFF05232A)

    /** Selected / active fill and outline for tabs, tiles and chips. */
    val AccentSoft = Color(0x245EC4D4)
    val AccentLine = Color(0x8C5EC4D4)

    val Danger = Color(0xFFFF8A80)

    /** Fill behind destructive buttons and error banners. */
    val DangerFill = Color(0xFF3A2326)

    /** State colours: one green, one amber, used for ready / paused everywhere. */
    val Success = Color(0xFF81C784)
    val Warning = Color(0xFFFFB74D)
    /** Text and icons drawn on a [Warning] fill. */
    val OnWarning = Color(0xFF261600)
}

/** One 4dp spacing grid for gaps and paddings: 4, 8, 12, 16, 24. */
object HzSpace {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
}

/**
 * Text sizes used by launcher pages (sp). Titles 16, section labels 13, body 12, captions 11,
 * mono micro labels 10. Pages pick from here instead of inventing a size.
 */
object HzText {
    const val PageTitle = 16f
    const val Section = 13f
    const val Body = 12f
    const val Caption = 11f
    const val Micro = 10f
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
