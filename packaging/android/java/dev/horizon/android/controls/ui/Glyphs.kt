package dev.horizon.android.controls.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph { CLOSE, PAUSE, EDIT, LAYOUTS, SLIDERS, CHEVRON, BACK, CHECK, GEAR, MENU }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = s * 0.11f
        val line = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(s * x, s * y)
        fun poly(vararg pts: Pair<Float, Float>) {
            val path = Path()
            pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(s * x, s * y) else path.lineTo(s * x, s * y) }
            drawPath(path, tint, style = line)
        }
        when (glyph) {
            Glyph.CLOSE -> {
                drawLine(tint, p(0.27f, 0.27f), p(0.73f, 0.73f), w, StrokeCap.Round)
                drawLine(tint, p(0.73f, 0.27f), p(0.27f, 0.73f), w, StrokeCap.Round)
            }
            Glyph.PAUSE -> {
                drawRoundRect(tint, p(0.29f, 0.2f), Size(s * 0.16f, s * 0.6f), CornerRadius(s * 0.05f))
                drawRoundRect(tint, p(0.55f, 0.2f), Size(s * 0.16f, s * 0.6f), CornerRadius(s * 0.05f))
            }
            Glyph.EDIT -> {
                drawLine(tint, p(0.3f, 0.7f), p(0.72f, 0.28f), w * 1.8f, StrokeCap.Round)
                drawLine(tint, p(0.2f, 0.84f), p(0.8f, 0.84f), w * 0.8f, StrokeCap.Round)
            }
            Glyph.LAYOUTS -> {
                val sq = Size(s * 0.28f, s * 0.28f)
                val st = Stroke(width = w * 0.8f, join = StrokeJoin.Round)
                drawRoundRect(tint, p(0.17f, 0.17f), sq, CornerRadius(s * 0.06f), st)
                drawRoundRect(tint, p(0.55f, 0.17f), sq, CornerRadius(s * 0.06f), st)
                drawRoundRect(tint, p(0.17f, 0.55f), sq, CornerRadius(s * 0.06f), st)
                drawRoundRect(tint, p(0.55f, 0.55f), sq, CornerRadius(s * 0.06f), st)
            }
            Glyph.SLIDERS -> {
                val rows = listOf(0.26f to 0.35f, 0.5f to 0.68f, 0.74f to 0.45f)
                rows.forEach { (y, k) ->
                    drawLine(tint, p(0.14f, y), p(0.86f, y), w * 0.7f, StrokeCap.Round)
                    drawCircle(tint, s * 0.1f, p(k, y))
                }
            }
            Glyph.CHEVRON -> poly(0.38f to 0.24f, 0.64f to 0.5f, 0.38f to 0.76f)
            Glyph.BACK -> poly(0.62f to 0.24f, 0.36f to 0.5f, 0.62f to 0.76f)
            Glyph.CHECK -> poly(0.2f to 0.52f, 0.42f to 0.72f, 0.8f to 0.3f)
            Glyph.GEAR -> {
                val c = Offset(s / 2f, s / 2f)
                drawCircle(tint, s * 0.1f, c)
                drawCircle(tint, s * 0.27f, c, style = Stroke(width = w))
                for (i in 0 until 8) {
                    val a = i * PI / 4.0
                    val x = cos(a).toFloat()
                    val y = sin(a).toFloat()
                    drawLine(tint, Offset(c.x + x * s * 0.3f, c.y + y * s * 0.3f), Offset(c.x + x * s * 0.43f, c.y + y * s * 0.43f), w * 1.5f, StrokeCap.Round)
                }
            }
            Glyph.MENU -> {
                drawLine(tint, p(0.2f, 0.3f), p(0.8f, 0.3f), w, StrokeCap.Round)
                drawLine(tint, p(0.2f, 0.5f), p(0.8f, 0.5f), w, StrokeCap.Round)
                drawLine(tint, p(0.2f, 0.7f), p(0.8f, 0.7f), w, StrokeCap.Round)
            }
        }
    }
}
