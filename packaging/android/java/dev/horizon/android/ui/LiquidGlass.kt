package dev.horizon.android.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.horizon.android.R
import dev.horizon.android.ui.theme.HzColor
import dev.horizon.android.ui.theme.HzShape
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Liquid glass, drawn by hand (no Material surfaces).
 *
 * The wallpaper is static, so its frosted version is made once: a 240 px copy, box-blurred on a background thread.
 * A glass surface draws the slice of that copy that lies exactly behind it, so the artwork shows through softly
 * instead of being hidden by a flat colour. On top go a dark tint (for legibility), a faint light sheen, a bright
 * top edge and a gradient rim that catches light on the top-left and bottom-right like a bevel.
 * There is no runtime blur and no offscreen layer: each surface is one scaled bitmap draw and a few gradients.
 *
 * [hzGlass] samples the backdrop and is for surfaces that sit on the wallpaper (dock, window, top bar, Home tiles).
 * [hzGlassInner] is for cards inside the window: the window already shows the frosted wallpaper, so they only add
 * tint, sheen and rim. That keeps scrolling lists free of per-frame position tracking.
 */
@Stable
internal class GlassBackdropState {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var rootSize by mutableStateOf(IntSize.Zero)
}

internal val LocalGlass = staticCompositionLocalOf { GlassBackdropState() }

@Composable
internal fun rememberGlassBackdropState(): GlassBackdropState {
    val resources = LocalContext.current.resources
    val state = remember { GlassBackdropState() }
    LaunchedEffect(Unit) {
        state.bitmap = withContext(Dispatchers.Default) { buildBlurredBackdrop(resources) }
    }
    return state
}

private fun buildBlurredBackdrop(resources: Resources): ImageBitmap? = runCatching {
    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
    val source = BitmapFactory.decodeResource(resources, R.drawable.hz_purple_wall, options) ?: return@runCatching null
    val w = 240
    val h = (w * source.height / source.width.toFloat()).roundToInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, w, h, true)
    if (small !== source) source.recycle()
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    small.recycle()
    val blurred = boxBlurArgb(pixels, w, h, radius = 3, passes = 3)
    Bitmap.createBitmap(blurred, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/** Separable box blur with clamped edges; alpha is forced opaque. Pure, so it can be checked without a device. */
internal fun boxBlurArgb(src: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3): IntArray {
    require(w > 0 && h > 0 && src.size == w * h) { "size mismatch" }
    if (radius <= 0 || passes <= 0) return src.copyOf()
    var a = src.copyOf()
    var b = IntArray(src.size)
    repeat(passes) {
        blurPass(a, b, w, h, radius, horizontal = true)
        blurPass(b, a, w, h, radius, horizontal = false)
    }
    return a
}

private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val stride = if (horizontal) 1 else w
    val lineStep = if (horizontal) w else 1
    val window = 2 * r + 1
    for (line in 0 until lines) {
        val base = line * lineStep
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val c = src[base + i.coerceIn(0, len - 1) * stride]
            sr += (c shr 16) and 255
            sg += (c shr 8) and 255
            sb += c and 255
        }
        for (i in 0 until len) {
            dst[base + i * stride] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            val out = src[base + (i - r).coerceIn(0, len - 1) * stride]
            val inn = src[base + (i + r + 1).coerceIn(0, len - 1) * stride]
            sr += ((inn shr 16) and 255) - ((out shr 16) and 255)
            sg += ((inn shr 8) and 255) - ((out shr 8) and 255)
            sb += (inn and 255) - (out and 255)
        }
    }
}

/** Rim light: bright on the top-left, soft in the middle, a second glint at the bottom-right. */
internal val HzGlassRim: Brush = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.28f))
)
private val GlassRimAccent: Brush = Brush.linearGradient(
    listOf(HzColor.Accent.copy(alpha = 0.95f), HzColor.Violet.copy(alpha = 0.40f), HzColor.AccentSecondary.copy(alpha = 0.40f), HzColor.AccentSecondary.copy(alpha = 0.85f))
)

private fun DrawScope.drawBackdropSlice(glass: GlassBackdropState, origin: Offset) {
    val bmp = glass.bitmap ?: return
    val root = glass.rootSize
    if (root.width <= 0 || root.height <= 0) return
    val scale = max(root.width / bmp.width.toFloat(), root.height / bmp.height.toFloat())
    val dw = bmp.width * scale
    val dh = bmp.height * scale
    val ox = (root.width - dw) / 2f - origin.x
    val oy = (root.height - dh) / 2f - origin.y
    drawImage(
        image = bmp,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        filterQuality = FilterQuality.Medium
    )
}

private fun sheenBrush(accent: Boolean): Brush =
    if (accent) Brush.linearGradient(listOf(HzColor.Violet.copy(alpha = 0.46f), HzColor.AccentSecondary.copy(alpha = 0.28f)))
    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent, HzColor.Violet.copy(alpha = 0.12f)))

private fun DrawScope.drawTopEdge(edge: Brush) {
    val inset = size.width * 0.12f
    drawLine(edge, Offset(inset, 1.5f), Offset(size.width - inset, 1.5f), strokeWidth = 1.5f)
}

/** Glass that samples the frosted wallpaper behind it. [darkness] is the legibility tint (0 = clear). */
internal fun Modifier.hzGlass(shape: Shape = HzShape.Panel, darkness: Float = 0.34f, accent: Boolean = false): Modifier = composed {
    val glass = LocalGlass.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Modifier
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawWithCache {
            val sheen = sheenBrush(accent)
            val inset = size.width * 0.12f
            val edge = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent),
                startX = inset,
                endX = (size.width - inset).coerceAtLeast(inset + 1f)
            )
            onDrawBehind {
                drawBackdropSlice(glass, origin)
                drawRect(HzColor.Background.copy(alpha = if (glass.bitmap == null) 0.9f else darkness))
                drawRect(sheen)
                drawTopEdge(edge)
            }
        }
        .border(1.dp, if (accent) GlassRimAccent else HzGlassRim, shape)
}

/** Glass for cards inside a glass window: tint, sheen, top edge and rim, with no backdrop sampling. */
internal fun Modifier.hzGlassInner(shape: Shape = HzShape.Panel, accent: Boolean = false, darkness: Float = 0.26f): Modifier =
    this
        .clip(shape)
        .drawWithCache {
            val sheen = sheenBrush(accent)
            val inset = size.width * 0.12f
            val edge = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                startX = inset,
                endX = (size.width - inset).coerceAtLeast(inset + 1f)
            )
            onDrawBehind {
                drawRect(HzColor.Background.copy(alpha = darkness))
                drawRect(sheen)
                drawTopEdge(edge)
            }
        }
        .border(1.dp, if (accent) GlassRimAccent else HzGlassRim, shape)
