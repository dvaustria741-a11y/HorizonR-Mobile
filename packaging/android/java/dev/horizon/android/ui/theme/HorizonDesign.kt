package dev.horizon.android.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.horizon.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Inter, subset to Latin (SIL OFL, see assets/licenses/Inter-OFL.txt). One family for the whole
 * launcher and the control editor, so every screen reads like one desktop app.
 */
val HzInter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_bold, FontWeight.ExtraBold)
)

/**
 * Pixelify Sans (SIL OFL, see assets/licenses/PixelifySans-OFL.txt), the same pixel face the desktop
 * launcher uses. Wordmark and a few headings only; body text stays in Inter so nothing small is set in it.
 */
val HzPixel = FontFamily(
    Font(R.font.pixelify_sans_medium, FontWeight.Medium),
    Font(R.font.pixelify_sans_bold, FontWeight.Bold)
)

/** Corner radii. Desktop apps use tight corners; the old pill shapes are gone. */
object HzRadius {
    val chip: Dp = 7.dp
    val control: Dp = 9.dp
    val card: Dp = 12.dp
}

/** Shared timings. Every animation in the launcher uses one of these, so motion feels the same everywhere. */
object HzMotion {
    /** Fast out, slow in: things that arrive. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Things that move between two resting places. */
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val Fast = 120
    const val Base = 200
    const val Slow = 300
}

/** Applies Inter to every Text that does not pick its own family. Wrap once at the root of a screen tree. */
@Composable
fun ProvideHzText(content: @Composable () -> Unit) {
    ProvideTextStyle(TextStyle(fontFamily = HzInter), content = content)
}

/** Same Typography, every style set in [family]. */
fun Typography.withFamily(family: FontFamily): Typography = copy(
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

/**
 * Fade and a short rise, staggered by [index] (capped, so a long list never waits).
 * Runs once per composition and only touches a graphics layer: no layout or recomposition per frame.
 */
fun Modifier.hzReveal(index: Int = 0, rise: Dp = 10.dp, stepMs: Int = 26): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * stepMs).toLong().coerceAtMost(220L))
        progress.animateTo(1f, tween(HzMotion.Base, easing = HzMotion.Emphasized))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise.toPx()
    }
}

/**
 * Flat neutral backdrop with a soft vignette. Drawn once and cached; nothing here animates,
 * so it costs no frame time. It replaces the drifting colour blobs.
 */
@Composable
fun HzBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val radius = maxOf(size.width, size.height) * 0.78f
                val vignette = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f)),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = radius
                )
                onDrawBehind { drawRect(vignette) }
            }
    )
}

/**
 * Thin desktop-style scrollbar for a [ScrollState] list. Fades in while the list moves and out after,
 * and is drawn in the draw phase only.
 */
fun Modifier.hzScrollbar(state: ScrollState, color: Color = Color.White.copy(alpha = 0.32f)): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.value }.collectLatest {
            alpha.snapTo(1f)
            delay(650)
            alpha.animateTo(0f, tween(260))
        }
    }
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max > 0 && alpha.value > 0.01f) {
            val view = size.height
            val total = view + max
            val thumb = (view * view / total).coerceAtLeast(24.dp.toPx()).coerceAtMost(view)
            val top = (state.value.toFloat() / max) * (view - thumb)
            val w = 3.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = color.alpha * alpha.value),
                topLeft = Offset(size.width - w - 1.dp.toPx(), top),
                size = Size(w, thumb),
                cornerRadius = CornerRadius(w / 2f, w / 2f)
            )
        }
    }
}

/** verticalScroll with the thin scrollbar attached; one call instead of two. */
@Composable
fun Modifier.hzVerticalScroll(): Modifier {
    val state = rememberScrollState()
    return this.hzScrollbar(state).verticalScroll(state)
}
