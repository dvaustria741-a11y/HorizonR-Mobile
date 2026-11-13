package dev.horizon.android.controls.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.R
import dev.horizon.android.controls.ControlLayouts
import dev.horizon.android.controls.ControlSettings
import dev.horizon.android.ui.theme.HzColor
import dev.horizon.android.ui.theme.HzMotion
import dev.horizon.android.ui.theme.HzShape
import kotlinx.coroutines.delay

/** The panel the menu handle opens in game: pause, edit the layout, switch layouts, quick sliders. */
@Composable
fun OverlayMenu(
    settings: ControlSettings,
    onClose: () -> Unit,
    onPause: () -> Unit,
    onEdit: () -> Unit,
    onLayout: (String) -> Unit,
    onSettingsChanged: () -> Unit,
) {
    val context = LocalContext.current
    val defaultName = stringResource(R.string.controls_default_name)
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(settings.layout) }
    var shown by remember { mutableStateOf(false) }
    var exit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val none = remember { MutableInteractionSource() }
    val panel = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(exit) {
        val run = exit ?: return@LaunchedEffect
        shown = false
        delay(190)
        run()
    }

    fun leave(after: () -> Unit) {
        if (exit == null) exit = after
    }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (shown) HzMotion.Medium else HzMotion.Short,
            easing = if (shown) HzMotion.Ease else HzMotion.EaseIn
        ),
        label = "menu"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.55f * progress)) }
            .clickable(interactionSource = none, indication = null) { leave(onClose) }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(280.dp)
                .graphicsLayer {
                    translationX = (1f - progress) * size.width * 0.2f
                    alpha = progress
                }
                .background(HzColor.Panel)
                .drawBehind { drawLine(HzColor.Hairline, Offset(0.5f, 0f), Offset(0.5f, size.height), 1f) }
                .clickable(interactionSource = panel, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            when (page) {
                1 -> {
                    BackRow { page = 0 }
                    Header(stringResource(R.string.menu_layouts))
                    ControlLayouts.list(context, defaultName).forEachIndexed { i, e ->
                        LayoutRow(e.name, selected == e.id, i) {
                            settings.layout = e.id
                            selected = e.id
                            onLayout(e.id)
                        }
                    }
                }
                2 -> {
                    BackRow { page = 0 }
                    Header(stringResource(R.string.menu_quick))
                    ControlsSettings(settings) { onSettingsChanged() }
                }
                else -> {
                    Header(stringResource(R.string.menu_title))
                    MenuRow(Glyph.CLOSE, stringResource(R.string.menu_close), 0) { leave(onClose) }
                    MenuRow(Glyph.PAUSE, stringResource(R.string.menu_pause), 1) { leave(onPause) }
                    MenuRow(Glyph.EDIT, stringResource(R.string.menu_edit), 2) { onEdit() }
                    MenuRow(Glyph.LAYOUTS, stringResource(R.string.menu_layouts), 3, chevron = true) { page = 1 }
                    MenuRow(Glyph.SLIDERS, stringResource(R.string.menu_quick), 4, chevron = true) { page = 2 }
                }
            }
        }
    }
}

@Composable
internal fun Header(title: String) {
    Row(modifier = Modifier.padding(start = 2.dp, top = 0.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(HzAccent)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun rowEnter(index: Int): Float {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(40L + index * 35L)
        on = true
    }
    val v by animateFloatAsState(if (on) 1f else 0f, tween(HzMotion.Medium, easing = HzMotion.Ease), label = "rowEnter")
    return v
}

@Composable
private fun MenuRow(glyph: Glyph, label: String, index: Int, chevron: Boolean = false, onClick: () -> Unit) {
    val enter = rowEnter(index)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "press")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter
                translationX = (1f - enter) * 16.dp.toPx()
                scaleX = scale
                scaleY = scale
            }
            .clip(HzShape.Panel)
            .background(if (pressed) HzColor.PanelHi else HzColor.Panel)
            .border(1.dp, HzColor.Hairline, HzShape.Panel)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(26.dp).clip(HzShape.Control).background(HzColor.PanelHi), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, HzAccent, Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) GlyphIcon(Glyph.CHEVRON, HzMuted, Modifier.size(14.dp))
    }
}

@Composable
private fun LayoutRow(name: String, active: Boolean, index: Int, onClick: () -> Unit) {
    val enter = rowEnter(index)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "press")
    val bg by animateFloatAsState(if (active) 1f else 0f, tween(200), label = "active")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter
                translationX = (1f - enter) * 16.dp.toPx()
                scaleX = scale
                scaleY = scale
            }
            .clip(HzShape.Panel)
            .background(if (pressed) HzColor.PanelHi else HzColor.Panel)
            .border(1.dp, if (active) HzAccent.copy(alpha = 0.7f * bg) else HzColor.Hairline, HzShape.Panel)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, modifier = Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (active) GlyphIcon(Glyph.CHECK, HzAccent, Modifier.size(15.dp))
    }
}

@Composable
private fun BackRow(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .clip(HzShape.Control)
            .background(if (pressed) HzColor.PanelHi else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.BACK, HzAccent, Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.menu_back), color = HzAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
