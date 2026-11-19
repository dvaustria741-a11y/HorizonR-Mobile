package dev.horizon.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.R
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.model.LauncherAccount
import dev.horizon.android.model.LauncherNavTab
import dev.horizon.android.ui.theme.HzBrush
import dev.horizon.android.ui.theme.HzColor
import dev.horizon.android.ui.theme.HzFont
import dev.horizon.android.ui.theme.HzMotion
import dev.horizon.android.ui.theme.HzShape
import dev.horizon.android.ui.theme.HzSpace
import dev.horizon.android.ui.theme.HzText
import kotlinx.coroutines.delay

/** Neutral dark surfaces shared by every launcher page: dark gray cards, a hairline stroke. */
internal val VoltGrayRoot = HzColor.Background
internal val VoltGrayCard = HzColor.Panel
internal val VoltGrayRow = HzColor.PanelHi
internal val VoltGrayStroke = HzColor.Hairline
internal val VoltGlass = HzColor.Glass
internal val VoltMutedText = HzColor.TextMuted
internal val VoltIconMuted = HzColor.TextIcon
internal val VoltTextMain = HzColor.TextPrimary

/**
 * White, transparent-background launcher icon.
 * Looks for a drawable called [name] (e.g. `ic_ui_mods`, cropped from the ChatGPT icon sheet);
 * if it is not in res/drawable-nodpi yet, a Material icon is drawn instead, also white.
 */
@Composable
internal fun VoltIcon(name: String, fallback: ImageVector, size: Dp, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val context = LocalContext.current
    val resId = remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
    if (resId != 0) {
        Icon(
            painter = painterResource(id = resId),
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size)
        )
    } else {
        Icon(
            imageVector = fallback,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}

/** Sheet icon used for a bottom/top nav tab (Home has no sheet icon and keeps its Material one). */
internal fun voltNavIconName(tab: LauncherNavTab): String = when (tab) {
    LauncherNavTab.VERSIONS -> "ic_ui_game"
    LauncherNavTab.STORE -> "ic_ui_store"
    LauncherNavTab.FILES -> "ic_ui_mods"
    LauncherNavTab.SERVERS -> "ic_ui_servers"
    LauncherNavTab.ACCOUNTS -> "ic_ui_skins"
    LauncherNavTab.SETTINGS -> "ic_ui_settings"
    LauncherNavTab.LOGS -> "ic_ui_logs"
    else -> "ic_ui_none"
}

/** One-shot hint for the Files page: which folder tab to open first (null = Mods). */
internal object FilesNav {
    @JvmField var pendingFolder: String? = null
}


/**
 * Insets for the pages beside the side rail: the rail takes the start edge, so pages keep top, end and bottom.
 */
@Composable
internal fun hzContentInsets(): WindowInsets =
    WindowInsets.safeDrawing.union(WindowInsets.displayCutout).only(WindowInsetsSides.End + WindowInsetsSides.Vertical)

/** Empty list: a tinted illustration (white art, coloured by the theme accent) with a plain-words message. */
@Composable
internal fun HzEmptyState(art: String, message: String, accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resId = remember(art) { context.resources.getIdentifier(art, "drawable", context.packageName) }
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (resId != 0) {
            Icon(painter = painterResource(id = resId), contentDescription = null, tint = accent.copy(alpha = 0.9f), modifier = Modifier.size(84.dp))
        }
        Text(text = message, fontSize = 13.sp, lineHeight = 18.sp, color = VoltMutedText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** The launcher's logo: the grass cube art when it is bundled, the drawn cube otherwise. */
@Composable
internal fun HzBrandCube(size: Dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(id = R.drawable.hz_logo_grass),
        contentDescription = "HorizonR",
        modifier = Modifier.size(size)
    )
}

/** Mono tag, e.g. a version number: hairline outline, no fill. */
@Composable
internal fun HzTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = HzFont.Mono,
        fontSize = HzText.Micro.sp,
        lineHeight = 12.sp,
        color = VoltMutedText,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, HzColor.Hairline, HzShape.Chip)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
internal fun HzPlayButton(
    installStatus: AssetInstallStatus,
    accent: Color,
    onLaunch: () -> Unit,
    launchProgress: Float? = null
) {
    val launching = launchProgress != null
    val progress by animateFloatAsState(
        targetValue = (if (launching) launchProgress ?: 0f else installStatus.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(HzMotion.Medium, easing = HzMotion.Ease),
        label = "PlayProgress"
    )
    val busy = installStatus.running || launching
    val fg = if (busy) accent else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(176.dp)
            .height(48.dp)
            .animatedPressScale(enabled = !launching, onClick = onLaunch)
            .clip(HzShape.Control)
            .then(if (busy) Modifier.background(HzColor.PanelHi) else Modifier.background(HzBrush.Signature))
            .drawBehind {
                if (busy) drawRect(color = accent.copy(alpha = 0.28f), size = Size(size.width * progress, size.height))
            }
    ) {
        if (launching) {
            // The loading bar: a track along the bottom of the button that fills with the launch progress.
            HzProgressBar(
                progress = progress,
                color = accent,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = if (launching) Modifier.padding(bottom = 6.dp) else Modifier
        ) {
            if (!launching) {
                VoltIcon(
                    name = if (installStatus.ready) "ic_ui_play" else "ic_ui_download",
                    fallback = if (installStatus.ready) Icons.Default.PlayArrow else Icons.Default.Download,
                    size = 19.dp,
                    tint = fg
                )
            }
            Text(
                text = if (launching) "LOADING"
                else if (installStatus.paused) "PAUSED"
                else if (installStatus.running) "${installStatus.percent}%"
                else if (installStatus.ready) "PLAY"
                else "INSTALL",
                fontFamily = if (busy) HzFont.Mono else null,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                color = fg
            )
        }
    }
}

internal fun hzStatusLine(s: AssetInstallStatus, playTime: String): String = when {
    s.ready -> if (playTime.isNotEmpty()) "Ready \u00B7 $playTime played" else "Ready"
    s.paused -> "Paused (${s.percent}%)"
    s.running -> "${s.percent}% \u00B7 ${s.speedText.ifBlank { "Downloading" }}"
    else -> "Needs download"
}

/**
 * Volt-style settings grid: cards flow into two columns (each card goes to the shorter column)
 * inside a vertically scrolling area.
 */
@Composable
internal fun VoltTwoColumnScroll(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable () -> Unit
) {
    val gap = verticalArrangement.spacing
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val totalW = constraints.maxWidth
        val colW = ((totalW - gapPx) / 2).coerceAtLeast(0)
        val childConstraints = androidx.compose.ui.unit.Constraints(
            minWidth = colW,
            maxWidth = colW,
            minHeight = 0,
            maxHeight = androidx.compose.ui.unit.Constraints.Infinity
        )
        val placeables = measurables.map { it.measure(childConstraints) }
        val heights = intArrayOf(0, 0)
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        placeables.forEachIndexed { i, pl ->
            val col = if (heights[0] <= heights[1]) 0 else 1
            xs[i] = col * (colW + gapPx)
            ys[i] = heights[col]
            heights[col] = ys[i] + pl.height + gapPx
        }
        val totalH = (maxOf(heights[0], heights[1]) - gapPx).coerceAtLeast(0)
        layout(totalW, totalH) {
            placeables.forEachIndexed { i, pl -> pl.placeRelative(xs[i], ys[i]) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Desktop-style settings parts: rows instead of cards, a vertical category list instead of chips
// ---------------------------------------------------------------------------------------------

/** 30 x 17 dp switch. The thumb slides and the track fades to the accent; drawn in one pass. */
@Composable
internal fun HzSwitch(checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(HzMotion.Short, easing = HzMotion.Ease),
        label = "HzSwitch"
    )
    val onAccent = if (accent.luminance() > 0.3f) HzColor.OnAccent else Color.White
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
            .drawBehind {
                drawRect(androidx.compose.ui.graphics.lerp(HzColor.Track, accent, t))
                val r = size.height / 2f - 3.dp.toPx()
                val left = size.height / 2f
                val right = size.width - size.height / 2f
                drawCircle(
                    color = androidx.compose.ui.graphics.lerp(HzColor.TextMuted, onAccent, t),
                    radius = r,
                    center = Offset(left + (right - left) * t, size.height / 2f)
                )
            }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                role = androidx.compose.ui.semantics.Role.Switch
            ) { onChange(!checked) }
    )
}

/**
 * Slider: 3 dp track, small thumb that grows with a soft halo while held, tick marks when the steps are few,
 * a light haptic tick on every new value, and a fill that glides on taps but follows the finger exactly on drags.
 * Everything is one draw pass, so dragging never recomposes more than the value it reports.
 */
@Composable
internal fun HzSlider(
    value: Int,
    range: IntRange,
    step: Int,
    accent: Color,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tickEvery: Int? = null,
    onDragging: (Boolean) -> Unit = {}
) {
    val emit by androidx.compose.runtime.rememberUpdatedState(onValue)
    val report by androidx.compose.runtime.rememberUpdatedState(onDragging)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(value) }
    val span = (range.last - range.first).coerceAtLeast(1)
    val target = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) androidx.compose.animation.core.snap<Float>() else tween(HzMotion.Short, easing = HzMotion.Ease),
        label = "HzSliderFill"
    )
    val grow by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(HzMotion.Short, easing = HzMotion.Ease),
        label = "HzSliderGrow"
    )
    val tickStep = tickEvery ?: if (span / step <= 20) step else 0
    fun pick(x: Float, w: Float): Int {
        val raw = range.first + (x / w).coerceIn(0f, 1f) * span
        val snapped = range.first + Math.round((raw - range.first) / step) * step
        return snapped.coerceIn(range.first, range.last)
    }
    fun commit(v: Int) {
        if (v != last) {
            last = v
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
        emit(v)
    }
    Box(
        modifier = modifier
            .height(38.dp)
            .pointerInput(range, step) {
                detectTapGestures { o -> commit(pick(o.x, size.width.toFloat())) }
            }
            .pointerInput(range, step) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        report(true)
                        commit(pick(o.x, size.width.toFloat()))
                    },
                    onDragEnd = { dragging = false; report(false) },
                    onDragCancel = { dragging = false; report(false) }
                ) { change, _ ->
                    change.consume()
                    commit(pick(change.position.x, size.width.toFloat()))
                }
            }
            .drawBehind {
                val cy = size.height / 2f
                val h = 3.dp.toPx()
                val thumb = (5.dp + 2.dp * grow).toPx()
                val pad = 8.dp.toPx()
                val trackW = size.width
                val fillX = (trackW * shown).coerceIn(thumb, trackW - thumb)
                drawRoundRect(HzColor.Track, Offset(0f, cy - h / 2f), Size(trackW, h), CornerRadius(h / 2f))
                if (tickStep > 0) {
                    var v = range.first
                    while (v <= range.last) {
                        val x = trackW * (v - range.first).toFloat() / span
                        val on = x <= fillX
                        drawLine(
                            color = if (on) Color.White.copy(alpha = 0.55f) else HzColor.Track,
                            start = Offset(x.coerceIn(0.5f, trackW - 0.5f), cy - 5.dp.toPx() * 0.5f - h),
                            end = Offset(x.coerceIn(0.5f, trackW - 0.5f), cy - h),
                            strokeWidth = 1.dp.toPx()
                        )
                        v += tickStep
                    }
                }
                drawRoundRect(accent, Offset(0f, cy - h / 2f), Size(fillX, h), CornerRadius(h / 2f))
                if (grow > 0.01f) drawCircle(accent.copy(alpha = 0.20f * grow), thumb + pad * 0.5f * grow, Offset(fillX, cy))
                drawCircle(accent, thumb, Offset(fillX, cy))
            }
    )
}

/** Two or three flat segments in one capsule. The selected segment fills with the accent colour. */
@Composable
internal fun HzSegmented(
    options: List<String>,
    selected: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val onAccent = if (accent.luminance() > 0.3f) HzColor.OnAccent else Color.White
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(HzShape.Control)
            .background(HzColor.PanelHi)
            .border(1.dp, HzColor.Hairline, HzShape.Control)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) accent else Color.Transparent, tween(HzMotion.Short), label = "SegBg")
            val fg by animateColorAsState(if (on) onAccent else VoltIconMuted, tween(HzMotion.Short), label = "SegFg")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(HzShape.Chip)
                    .background(bg)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { onSelect(i) }
                    .padding(horizontal = 10.dp)
            ) {
                Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
            }
        }
    }
}

/**
 * One setting: title and a short description on the left, the control on the right, a hairline under it.
 * Pass [onClick] to make the whole row the touch target (toggles).
 */
@Composable
internal fun HzSettingRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    control: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ) else Modifier)
            .drawBehind {
                drawLine(HzColor.Hairline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = HzSpace.XS, vertical = HzSpace.S + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HzSpace.L)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = title, fontSize = HzText.Section.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = HzColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) {
                Text(text = description, fontSize = HzText.Caption.sp, lineHeight = 15.sp, color = HzColor.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        control()
    }
}

/** Small caps label that opens a group of settings rows; the accent tick ties the group to the selected category. */
@Composable
internal fun HzSectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = HzSpace.L, bottom = HzSpace.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = HzFont.Mono,
            fontSize = HzText.Micro.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
            color = HzColor.TextMuted,
            maxLines = 1
        )
    }
}

/** A mono value box with a dropdown arrow; the caller anchors the menu to it. */
@Composable
internal fun HzSelectBox(text: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val rot by animateFloatAsState(if (open) 180f else 0f, tween(HzMotion.Short, easing = HzMotion.Ease), label = "HzSelArrow")
    Row(
        modifier = modifier
            .widthIn(min = 110.dp, max = 200.dp)
            .height(38.dp)
            .animatedPressScale(pressedScale = 0.97f, onClick = onClick)
            .clip(HzShape.Control)
            .background(HzColor.PanelHi)
            .border(1.dp, HzColor.Hairline, HzShape.Control)
            .padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = VoltTextMain,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = VoltMutedText,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rot }
        )
    }
}

/** Vertical category list for master-detail pages. Selected row gets a flat fill and a slim accent bar. */
@Composable
internal fun <T> HzCategoryList(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    accent: Color,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(176.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { item ->
            val on = item == selected
            val sel by animateFloatAsState(if (on) 1f else 0f, tween(HzMotion.Short, easing = HzMotion.Ease), label = "CatSel")
            val tint by animateColorAsState(if (on) Color.White else VoltMutedText, tween(HzMotion.Short), label = "CatTint")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .animatedPressScale(pressedScale = 0.98f) { onSelect(item) }
                    .clip(HzShape.Control)
                    .drawBehind {
                        if (sel > 0.01f) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(listOf(HzColor.Violet.copy(alpha = 0.42f), HzColor.AccentSecondary.copy(alpha = 0.28f))),
                                alpha = sel,
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = icon(item), contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                Text(text = label(item), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 28 dp square icon button on a flat fill. The one icon-button style for toolbars. */
@Composable
internal fun HzIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = HzColor.TextIcon,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(38.dp)
            .animatedPressScale(pressedScale = 0.94f, onClick = onClick)
            .clip(HzShape.Control)
            .background(HzColor.PanelHi)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** Text + icon toolbar button, 28 dp tall. [filled] uses the accent colour. */
@Composable
internal fun HzToolbarButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val onAccent = if (accent.luminance() > 0.3f) HzColor.OnAccent else Color.White
    Row(
        modifier = Modifier
            .height(38.dp)
            .animatedPressScale(pressedScale = 0.96f, onClick = onClick)
            .clip(HzShape.Control)
            .background(if (filled) accent else HzColor.PanelHi)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (filled) onAccent else accent, modifier = Modifier.size(14.dp))
        Text(text = label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (filled) onAccent else VoltTextMain, maxLines = 1)
    }
}


// ---------------------------------------------------------------------------------------------
// Shared page components. Every launcher page builds from these so spacing, radii, text sizes and
// button styles stay identical from page to page.
// ---------------------------------------------------------------------------------------------

internal enum class HzButtonKind { Primary, Secondary, Danger }

/** The one button: icon and label, 40dp tall. Primary is the accent, Secondary a raised panel, Danger a red one. */
@Composable
internal fun HzButton(
    label: String,
    icon: ImageVector,
    kind: HzButtonKind,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val onAccent = if (accent.luminance() > 0.3f) HzColor.OnAccent else Color.White
    val fill = when (kind) {
        HzButtonKind.Primary -> accent
        HzButtonKind.Secondary -> HzColor.PanelHi
        HzButtonKind.Danger -> HzColor.DangerFill
    }
    val text = when (kind) {
        HzButtonKind.Primary -> onAccent
        HzButtonKind.Secondary -> VoltTextMain
        HzButtonKind.Danger -> HzColor.Danger
    }
    val glyph = when (kind) {
        HzButtonKind.Primary -> onAccent
        HzButtonKind.Secondary -> accent
        HzButtonKind.Danger -> HzColor.Danger
    }
    val outline = when (kind) {
        HzButtonKind.Primary -> Color.Transparent
        HzButtonKind.Secondary -> HzColor.Hairline
        HzButtonKind.Danger -> HzColor.Danger.copy(alpha = 0.22f)
    }
    Row(
        modifier = modifier
            .height(40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .animatedPressScale(enabled = enabled, pressedScale = 0.97f, onClick = onClick)
            .clip(HzShape.Control)
            .background(fill)
            .border(1.dp, outline, HzShape.Control)
            .padding(horizontal = HzSpace.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = glyph, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A raised panel with a hairline outline and 12dp of padding; children are 12dp apart. */
@Composable
internal fun HzCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HzShape.Panel)
            .background(HzColor.Panel)
            .border(1.dp, HzColor.Hairline, HzShape.Panel)
            .padding(HzSpace.M),
        verticalArrangement = Arrangement.spacedBy(HzSpace.M),
        content = content
    )
}

/** Page title with an optional one-line subtitle; [trailing] is pinned to the right edge. */
@Composable
internal fun HzPageHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = VoltTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = VoltMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}

/** Small pill with a status dot, e.g. Ready, Paused, Offline. */
@Composable
internal fun HzStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Text(text = text, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** Thin progress bar; [progress] is 0..1 and should already be animated by the caller. */
@Composable
internal fun HzProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(HzColor.PanelHi)
            .drawBehind {
                drawRect(brush = HzBrush.Signature, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
            }
    )
}

/** Label on the left, mono value on the right, hairline underneath: one line of a spec table. */
@Composable
internal fun HzSpecRow(label: String, value: String, valueColor: Color = VoltTextMain) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(HzColor.Hairline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.5.sp, lineHeight = 15.sp, color = VoltMutedText, modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontFamily = HzFont.Mono,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
