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
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.model.LauncherAccount
import dev.horizon.android.model.LauncherNavTab
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

private data class HzNavEntry(val tab: LauncherNavTab, val label: String, val icon: String, val fallback: ImageVector)

/**
 * Side rail: brand on top, one labelled row per page, logs and the files app pinned at the bottom.
 * Desktop launchers keep navigation on the left and give the page the full height; rows are 38dp tall with a
 * visible label. The selected row gets a soft accent fill and a 3dp bar on its leading edge; only colours animate.
 */
@Composable
internal fun HzSideRail(
    selectedTab: LauncherNavTab,
    installStatus: AssetInstallStatus,
    accent: Color,
    onSelectTab: (LauncherNavTab) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = remember {
        listOf(
            HzNavEntry(LauncherNavTab.HOME, "Home", "ic_ui_none", Icons.Default.Home),
            HzNavEntry(LauncherNavTab.VERSIONS, "Game", "ic_ui_game", Icons.Default.Download),
            HzNavEntry(LauncherNavTab.STORE, "Store", "ic_ui_store", Icons.Default.Storefront),
            HzNavEntry(LauncherNavTab.FILES, "Mods", "ic_ui_mods", Icons.Default.Extension),
            HzNavEntry(LauncherNavTab.SERVERS, "Servers", "ic_ui_servers", Icons.Default.Dns),
            HzNavEntry(LauncherNavTab.ACCOUNTS, "Skins", "ic_ui_skins", Icons.Default.Checkroom),
            HzNavEntry(LauncherNavTab.SETTINGS, "Settings", "ic_ui_settings", Icons.Default.Settings)
        )
    }
    Column(
        modifier = modifier
            .width(150.dp)
            .fillMaxHeight()
            .background(HzColor.Rail)
            .drawBehind {
                drawLine(HzColor.Hairline, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
            }
            .windowInsetsPadding(
                WindowInsets.safeDrawing.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
            )
            .padding(horizontal = HzSpace.S, vertical = HzSpace.S),
        verticalArrangement = Arrangement.spacedBy(HzSpace.XS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = HzSpace.XS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
        ) {
            HzBrandCube(size = 24.dp)
            Text(text = "HorizonR", fontSize = HzText.Section.sp, fontWeight = FontWeight.ExtraBold, color = HzColor.TextPrimary, maxLines = 1)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            entries.forEach { e ->
                HzRailItem(
                    entry = e,
                    selected = e.tab == selectedTab,
                    label = if (e.tab == LauncherNavTab.VERSIONS && installStatus.running) "${installStatus.percent}%" else e.label,
                    accent = accent,
                    onClick = { onSelectTab(e.tab) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(HzColor.Hairline, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }
                .padding(top = HzSpace.XS),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            HzRailFooterButton("ic_ui_logs", Icons.Default.Terminal, selectedTab == LauncherNavTab.LOGS, accent, Modifier.weight(1f)) {
                onSelectTab(LauncherNavTab.LOGS)
            }
            HzRailFooterButton("ic_ui_folder", Icons.Default.Folder, false, accent, Modifier.weight(1f), onOpenFolder)
        }
    }
}

@Composable
private fun HzRailItem(entry: HzNavEntry, selected: Boolean, label: String, accent: Color, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else HzColor.TextMuted,
        animationSpec = tween(HzMotion.Short),
        label = "RailTint"
    )
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(HzMotion.Short, easing = HzMotion.Ease),
        label = "RailSelected"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .animatedPressScale(pressedScale = 0.98f, onClick = onClick)
            .clip(HzShape.Control)
            .drawBehind {
                if (sel > 0.01f) {
                    drawRect(HzColor.AccentSoft.copy(alpha = HzColor.AccentSoft.alpha * sel))
                    drawRoundRect(
                        color = accent.copy(alpha = sel),
                        topLeft = Offset(0f, size.height * 0.22f),
                        size = Size(3.dp.toPx(), size.height * 0.56f),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
    ) {
        VoltIcon(name = entry.icon, fallback = entry.fallback, size = 18.dp, tint = tint)
        Text(
            text = label,
            fontSize = HzText.Body.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) HzColor.TextPrimary else HzColor.TextMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun HzRailFooterButton(name: String, fallback: ImageVector, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else HzColor.TextMuted,
        animationSpec = tween(HzMotion.Short),
        label = "RailFooterTint"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(38.dp)
            .animatedPressScale(pressedScale = 0.96f, onClick = onClick)
            .clip(HzShape.Control)
            .background(if (selected) HzColor.AccentSoft else Color.Transparent)
    ) {
        VoltIcon(name = name, fallback = fallback, size = 18.dp, tint = tint)
    }
}

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
    val context = LocalContext.current
    val resId = remember { context.resources.getIdentifier("hz_logo_grass", "drawable", context.packageName) }
    if (resId != 0) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = resId),
            contentDescription = "HorizonR",
            modifier = Modifier.size(size)
        )
    } else {
        MinecraftGrassCubeIcon(size = size)
    }
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

private val HOME_TILE_HEIGHT = 52.dp

/** One tile of the Home quick-access grid. Tiles fade in one after the other, once. */
@Composable
private fun HzHomeTile(
    label: String,
    hint: String?,
    iconName: String,
    fallback: ImageVector,
    index: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + index * 40L)
        enter.animateTo(1f, tween(HzMotion.Medium, easing = HzMotion.Ease))
    }
    Row(
        modifier = modifier
            .height(HOME_TILE_HEIGHT)
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 8.dp.toPx()
            }
            .animatedPressScale(pressedScale = 0.97f, onClick = onClick)
            .clip(HzShape.Control)
            .background(HzColor.PanelHi.copy(alpha = 0.6f))
            .border(1.dp, HzColor.Hairline, HzShape.Control)
            .padding(horizontal = HzSpace.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 20.dp, tint = VoltIconMuted)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = HzText.Section.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = VoltTextMain,
                maxLines = 1
            )
            if (hint != null) {
                Text(
                    text = hint,
                    fontFamily = HzFont.Mono,
                    fontSize = HzText.Micro.sp,
                    lineHeight = 12.sp,
                    color = VoltMutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HzPlayButton(installStatus: AssetInstallStatus, accent: Color, onLaunch: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = (installStatus.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(HzMotion.Medium, easing = HzMotion.Ease),
        label = "PlayProgress"
    )
    val onAccent = if (accent.luminance() > 0.45f) HzColor.OnAccent else Color.White
    val busy = installStatus.running
    val fg = if (busy) accent else onAccent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(176.dp)
            .height(48.dp)
            .animatedPressScale(onClick = onLaunch)
            .clip(HzShape.Control)
            .background(if (busy) HzColor.PanelHi else accent)
            .drawBehind {
                if (busy) drawRect(color = accent.copy(alpha = 0.28f), size = Size(size.width * progress, size.height))
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VoltIcon(
                name = if (installStatus.ready) "ic_ui_play" else "ic_ui_download",
                fallback = if (installStatus.ready) Icons.Default.PlayArrow else Icons.Default.Download,
                size = 19.dp,
                tint = fg
            )
            Text(
                text = if (installStatus.paused) "PAUSED"
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

@Composable
private fun HzGhostButton(label: String, iconName: String, fallback: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .animatedPressScale(onClick = onClick)
            .clip(HzShape.Control)
            .background(HzColor.Glass)
            .border(1.dp, HzColor.Hairline, HzShape.Control)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 18.dp, tint = VoltIconMuted)
        Text(text = label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = VoltTextMain, maxLines = 1)
    }
}

private fun hzStatusLine(s: AssetInstallStatus, playTime: String): String = when {
    s.ready -> if (playTime.isNotEmpty()) "Ready \u00B7 $playTime played" else "Ready"
    s.paused -> "Paused (${s.percent}%)"
    s.running -> "${s.percent}% \u00B7 ${s.speedText.ifBlank { "Downloading" }}"
    else -> "Needs download"
}

/**
 * Home: the wallpaper stays clear. A quote sits top left, a quick-access grid (2 x 2 plus a full-width Skins tile)
 * top right, and one full-width dock along the bottom carries the selected version, its status and PLAY.
 * The rail is drawn by the shell.
 */
@Composable
internal fun VoltHomeLayer(
    accent: Color,
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    installStatus: AssetInstallStatus,
    quote: String?,
    onQuoteTap: () -> Unit,
    onNavigate: (LauncherNavTab) -> Unit,
    onOpenFolder: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playTime = PlayTime.label(context)
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(hzContentInsets())
            .padding(horizontal = HzSpace.L, vertical = HzSpace.M)
    ) {
        Box(modifier = Modifier.align(Alignment.TopStart).widthIn(max = 360.dp)) {
            Crossfade(targetState = quote, animationSpec = tween(HzMotion.Medium), label = "HomeQuote") { q ->
                if (q != null) {
                    Text(
                        text = "\u201C$q\u201D",
                        fontSize = HzText.Caption.sp,
                        lineHeight = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.66f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.animatedPressScale(onClick = onQuoteTap)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(256.dp)
                .clip(HzShape.Panel)
                .background(HzColor.Glass)
                .border(1.dp, HzColor.Hairline, HzShape.Panel)
                .padding(HzSpace.S),
            verticalArrangement = Arrangement.spacedBy(HzSpace.S)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(HzSpace.S)) {
                HzHomeTile("Mods", null, "ic_ui_mods", Icons.Default.Extension, 0, accent, Modifier.weight(1f)) { onNavigate(LauncherNavTab.FILES) }
                HzHomeTile("Shaders", null, "ic_ui_shaders", Icons.Default.WbSunny, 1, accent, Modifier.weight(1f)) { onNavigate(LauncherNavTab.STORE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HzSpace.S)) {
                HzHomeTile("Worlds", null, "ic_ui_worlds", Icons.Default.Public, 2, accent, Modifier.weight(1f)) {
                    FilesNav.pendingFolder = "saves"
                    onNavigate(LauncherNavTab.FILES)
                }
                HzHomeTile("Servers", null, "ic_ui_servers", Icons.Default.Dns, 3, accent, Modifier.weight(1f)) { onNavigate(LauncherNavTab.SERVERS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HzSpace.S)) {
                HzHomeTile("Skins", activeAccount.username, "ic_ui_skins", Icons.Default.Checkroom, 4, accent, Modifier.fillMaxWidth()) { onNavigate(LauncherNavTab.ACCOUNTS) }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .clip(HzShape.Panel)
                .background(HzColor.Glass)
                .border(1.dp, HzColor.Hairline, HzShape.Panel)
                .padding(HzSpace.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HzSpace.M)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HzSpace.XS)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HzSpace.S)) {
                    Text(
                        text = "Minecraft 26.3",
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    HzTag("NATIVE RUST")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HzSpace.S)) {
                    val dot = when {
                        installStatus.ready -> HzColor.Success
                        installStatus.paused -> HzColor.Warning
                        installStatus.running -> accent
                        else -> VoltMutedText
                    }
                    HzStatusPill(text = hzStatusLine(installStatus, ""), color = dot)
                    if (playTime.isNotEmpty()) {
                        Text(
                            text = "$playTime played",
                            fontFamily = HzFont.Mono,
                            fontSize = HzText.Caption.sp,
                            lineHeight = 14.sp,
                            color = VoltMutedText,
                            maxLines = 1
                        )
                    }
                }
                if (installStatus.running) {
                    val bar by animateFloatAsState(
                        targetValue = (installStatus.percent / 100f).coerceIn(0f, 1f),
                        animationSpec = tween(HzMotion.Medium, easing = HzMotion.Ease),
                        label = "HomeBar"
                    )
                    HzProgressBar(progress = bar, color = accent)
                }
            }
            HzGhostButton("Game files", "ic_ui_folder", Icons.Default.Folder) { onNavigate(LauncherNavTab.VERSIONS) }
            HzPlayButton(installStatus = installStatus, accent = accent, onLaunch = onLaunch)
        }
    }
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
    val onAccent = if (accent.luminance() > 0.45f) HzColor.OnAccent else Color.White
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
            .drawBehind {
                drawRect(androidx.compose.ui.graphics.lerp(Color(0xFF2C313B), accent, t))
                val r = size.height / 2f - 3.dp.toPx()
                val left = size.height / 2f
                val right = size.width - size.height / 2f
                drawCircle(
                    color = androidx.compose.ui.graphics.lerp(Color(0xFF9AA1AB), onAccent, t),
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
                drawRoundRect(Color(0xFF2C313B), Offset(0f, cy - h / 2f), Size(trackW, h), CornerRadius(h / 2f))
                if (tickStep > 0) {
                    var v = range.first
                    while (v <= range.last) {
                        val x = trackW * (v - range.first).toFloat() / span
                        val on = x <= fillX
                        drawLine(
                            color = if (on) Color.White.copy(alpha = 0.55f) else Color(0xFF4A5260),
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
    val onAccent = if (accent.luminance() > 0.45f) HzColor.OnAccent else Color.White
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
        Box(modifier = Modifier.size(width = 3.dp, height = 11.dp).clip(HzShape.Chip).background(HzColor.Accent))
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
                    .height(38.dp)
                    .animatedPressScale(pressedScale = 0.98f) { onSelect(item) }
                    .clip(HzShape.Control)
                    .drawBehind {
                        if (sel > 0.01f) {
                            drawRoundRect(HzColor.PanelHi.copy(alpha = sel), cornerRadius = CornerRadius(6.dp.toPx()))
                            val barH = size.height * 0.5f * sel
                            drawRoundRect(
                                accent.copy(alpha = sel), Offset(0f, (size.height - barH) / 2f),
                                Size(2.5.dp.toPx(), barH), CornerRadius(2.dp.toPx())
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
    tint: Color = Color(0xFFD5DEE5),
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
    val onAccent = if (accent.luminance() > 0.45f) HzColor.OnAccent else Color.White
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
    val onAccent = if (accent.luminance() > 0.45f) HzColor.OnAccent else Color.White
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
                drawRect(color = color, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
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
