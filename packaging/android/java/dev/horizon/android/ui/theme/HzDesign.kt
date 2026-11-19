package dev.horizon.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Brush
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
 * Default theme tokens (Galaxy Purple, from the RussoViller Purple theme): night-violet surfaces, lavender accent, pink gradient end. Every launcher and editor
 * colour that is not a per-theme palette colour comes from here, so a new theme only has to change this block.
 */
object HzColor {
    /** Page background behind everything: deep night violet. */
    val Background = Color(0xFF0B0724)

    /** Side rail and bars. */
    val Rail = Color(0xFF090620)

    /** Cards, dialogs, side panels. */
    val Panel = Color(0xFF150E36)

    /** Raised or selected surfaces inside a Panel: rows, chips, fields. */
    val PanelHi = Color(0xFF1F1548)

    /** Recessed wells: log boxes, text fields, anything that sits inside a Panel. */
    val PanelLow = Color(0xFF0C0828)

    /** Panels drawn over wallpaper artwork. */
    val Glass = Color(0xCC120A33)

    val Hairline = Color(0x2EB388FF)

    /** Hairline for focused or selected outlines and for dividers on bright surfaces. */
    val HairlineStrong = Color(0x59C9A8FF)

    val TextPrimary = Color(0xFFF3EEFF)
    val TextIcon = Color(0xFFCFC3F0)
    val TextMuted = Color(0xFF9A8FC0)

    /** Lavender: the everyday accent (selected tab, links, progress). */
    val Accent = Color(0xFFB388FF)
    /** Pink: the second end of the signature gradient. */
    val AccentSecondary = Color(0xFFFF63C8)
    val OnAccent = Color(0xFF1A0A3D)

    /** Blue-violet start of the signature gradient. */
    val Violet = Color(0xFF6A4BFF)

    /** Selected / active fill and outline for tabs, tiles and chips. */
    val AccentSoft = Color(0x33B388FF)
    val AccentLine = Color(0x99B388FF)

    val Danger = Color(0xFFFF8A9E)

    /** Fill behind destructive buttons and error banners. */
    val DangerFill = Color(0xFF3A1B35)

    /** State colours: one green, one amber, used for ready / paused everywhere. */
    val Success = Color(0xFF81C784)
    val Warning = Color(0xFFFFB74D)
    /** Text and icons drawn on a [Warning] fill. */
    val OnWarning = Color(0xFF261600)

    /** Unfilled track of switches and sliders. */
    val Track = Color(0xFF2B2252)
}

/** The launcher's signature violet-to-pink gradient (play button, selected rail row, progress). */
object HzBrush {
    val Signature: Brush get() = Brush.horizontalGradient(listOf(HzColor.Violet, HzColor.Accent, HzColor.AccentSecondary))
    val Vertical: Brush get() = Brush.verticalGradient(listOf(HzColor.Violet, HzColor.AccentSecondary))
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

/** Soft but compact radii: rounded like the anime theme preview, still desktop-dense. */
object HzShape {
    val Panel = RoundedCornerShape(16.dp)
    val Control = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(8.dp)
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
