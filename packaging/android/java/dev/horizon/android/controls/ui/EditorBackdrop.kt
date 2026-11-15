package dev.horizon.android.controls.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.horizon.android.ui.theme.HzColor

/**
 * Schematic gameplay view behind the editable controls: sky and ground split, crosshair, hotbar, health and hunger rows.
 * It only tells where the in-game HUD sits so controls are not placed on top of it; it is not to scale with Minecraft.
 */
@Composable
fun EditorBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.52f
        drawRect(HzColor.Panel, size = Size(w, horizon))
        drawRect(HzColor.PanelLow, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
        drawLine(HzColor.HairlineStrong, Offset(0f, horizon), Offset(w, horizon), 1.dp.toPx())

        val cx = w / 2f
        val cy = h / 2f
        val arm = 8.dp.toPx()
        drawLine(HzColor.HairlineStrong, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.5.dp.toPx())
        drawLine(HzColor.HairlineStrong, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.5.dp.toPx())

        val slot = 36.dp.toPx()
        val gap = 2.dp.toPx()
        val barW = 9 * slot + 8 * gap
        val left = cx - barW / 2f
        val top = h - slot - 6.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        repeat(9) { i ->
            val x = left + i * (slot + gap)
            drawRoundRect(HzColor.Hairline, Offset(x, top), Size(slot, slot), radius)
            drawRoundRect(HzColor.HairlineStrong, Offset(x, top), Size(slot, slot), radius, style = Stroke(1.dp.toPx()))
        }

        val cell = 7.dp.toPx()
        val cellGap = 2.dp.toPx()
        val rowTop = top - cell - 6.dp.toPx()
        repeat(10) { i ->
            drawRoundRect(HzColor.Danger.copy(alpha = 0.35f), Offset(left + i * (cell + cellGap), rowTop), Size(cell, cell), CornerRadius(1.5.dp.toPx()))
            drawRoundRect(HzColor.Warning.copy(alpha = 0.35f), Offset(left + barW - (i + 1) * (cell + cellGap) + cellGap, rowTop), Size(cell, cell), CornerRadius(1.5.dp.toPx()))
        }
    }
}
