package dev.horizon.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.delay

/** Neutral dark surfaces shared by every launcher page: dark gray cards, a hairline stroke. */
internal val VoltGrayRoot = Color(0xFF0D0F16)
internal val VoltGrayCard = Color(0xFF1A1D25)
internal val VoltGrayRow = Color(0xFF272C36)
internal val VoltGrayStroke = Color(0x24FFFFFF)
internal val VoltGlass = Color(0xCA16191F)
internal val VoltMutedText = Color(0xFF8F969B)
internal val VoltIconMuted = Color(0xFFC6CCC9)
internal val VoltTextMain = Color(0xFFF2F5F3)

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
 * Insets for everything to the right of the rail. The rail takes the left edge (camera cutout included),
 * so pages only keep the right edge and the top and bottom.
 */
@Composable
internal fun hzContentInsets(): WindowInsets =
    WindowInsets.safeDrawing.union(WindowInsets.displayCutout).only(WindowInsetsSides.End + WindowInsetsSides.Vertical)

private data class HzRailEntry(val tab: LauncherNavTab, val label: String, val icon: String, val fallback: ImageVector)

/**
 * Desktop-style sidebar: brand on top, one row per page, logs and folder at the bottom.
 * The selected page gets a flat highlight and a slim accent bar; nothing glows.
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
            HzRailEntry(LauncherNavTab.HOME, "Home", "ic_ui_none", Icons.Default.Home),
            HzRailEntry(LauncherNavTab.VERSIONS, "Game", "ic_ui_game", Icons.Default.Download),
            HzRailEntry(LauncherNavTab.STORE, "Store", "ic_ui_store", Icons.Default.Storefront),
            HzRailEntry(LauncherNavTab.FILES, "Mods", "ic_ui_mods", Icons.Default.Extension),
            HzRailEntry(LauncherNavTab.SERVERS, "Servers", "ic_ui_servers", Icons.Default.Dns),
            HzRailEntry(LauncherNavTab.ACCOUNTS, "Skins", "ic_ui_skins", Icons.Default.Checkroom),
            HzRailEntry(LauncherNavTab.SETTINGS, "Settings", "ic_ui_settings", Icons.Default.Settings)
        )
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(HzColor.Rail)
            .drawBehind {
                drawLine(
                    color = HzColor.Hairline,
                    start = Offset(size.width - 0.5f, 0f),
                    end = Offset(size.width - 0.5f, size.height),
                    strokeWidth = 1f
                )
            }
            .windowInsetsPadding(
                WindowInsets.safeDrawing.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
            )
            .width(54.dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MinecraftGrassCubeIcon(size = 20.dp)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            entries.forEach { e ->
                HzRailItem(
                    entry = e,
                    selected = e.tab == selectedTab,
                    label = if (e.tab == LauncherNavTab.VERSIONS && installStatus.running) "${installStatus.percent}%" else e.label,
                    mono = e.tab == LauncherNavTab.VERSIONS && installStatus.running,
                    accent = accent,
                    onClick = { onSelectTab(e.tab) }
                )
            }
        }
        HzRailIconButton("ic_ui_logs", Icons.Default.Terminal, selectedTab == LauncherNavTab.LOGS, accent) {
            onSelectTab(LauncherNavTab.LOGS)
        }
        HzRailIconButton("ic_ui_folder", Icons.Default.Folder, false, accent, onOpenFolder)
    }
}

@Composable
private fun HzRailItem(entry: HzRailEntry, selected: Boolean, label: String, mono: Boolean, accent: Color, onClick: () -> Unit) {
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(HzMotion.Short, easing = HzMotion.Ease),
        label = "RailSel"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) Color.White else VoltMutedText,
        animationSpec = tween(HzMotion.Short),
        label = "RailTint"
    )
    Column(
        modifier = Modifier
            .padding(vertical = 1.dp)
            .width(46.dp)
            .height(36.dp)
            .animatedPressScale(pressedScale = 0.94f, onClick = onClick)
            .clip(HzShape.Control)
            .drawBehind {
                if (sel > 0.01f) {
                    drawRoundRect(color = HzColor.PanelHi.copy(alpha = sel), cornerRadius = CornerRadius(6.dp.toPx()))
                    val barH = size.height * 0.5f * sel
                    drawRoundRect(
                        color = accent.copy(alpha = sel),
                        topLeft = Offset(0f, (size.height - barH) / 2f),
                        size = Size(2.5.dp.toPx(), barH),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VoltIcon(name = entry.icon, fallback = entry.fallback, size = 16.dp, tint = tint)
        Text(
            text = label,
            fontFamily = if (mono) HzFont.Mono else null,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1
        )
    }
}

@Composable
private fun HzRailIconButton(name: String, fallback: ImageVector, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (selected) accent else VoltMutedText,
        animationSpec = tween(HzMotion.Short),
        label = "RailBtnTint"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 1.dp)
            .size(width = 46.dp, height = 28.dp)
            .animatedPressScale(pressedScale = 0.94f, onClick = onClick)
            .clip(HzShape.Control)
    ) {
        VoltIcon(name = name, fallback = fallback, size = 15.dp, tint = tint)
    }
}

/** Mono tag, e.g. a version number: hairline outline, no fill. */
@Composable
private fun HzTag(text: String) {
    Text(
        text = text,
        fontFamily = HzFont.Mono,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        color = VoltMutedText,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, HzColor.Hairline, HzShape.Chip)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
private fun HzChip(label: String, icon: ImageVector, active: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (active) accent else VoltIconMuted,
        animationSpec = tween(HzMotion.Short),
        label = "ChipTint"
    )
    Row(
        modifier = modifier
            .height(24.dp)
            .animatedPressScale(pressedScale = 0.96f, onClick = onClick)
            .clip(HzShape.Control)
            .background(HzColor.Glass)
            .border(1.dp, if (active) accent.copy(alpha = 0.55f) else HzColor.Hairline, HzShape.Control)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(text = label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = VoltTextMain, maxLines = 1)
    }
}

/** One row of the Home shortcut list. Rows slide in one after the other, once. */
@Composable
private fun HzShortcut(label: String, hint: String?, iconName: String, fallback: ImageVector, index: Int, onClick: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + index * 45L)
        enter.animateTo(1f, tween(HzMotion.Medium, easing = HzMotion.Ease))
    }
    Row(
        modifier = Modifier
            .width(184.dp)
            .height(34.dp)
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * 14.dp.toPx()
            }
            .animatedPressScale(pressedScale = 0.97f, onClick = onClick)
            .clip(HzShape.Panel)
            .background(HzColor.Glass)
            .border(1.dp, HzColor.Hairline, HzShape.Panel)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 15.dp, tint = VoltIconMuted)
        Text(
            text = label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = VoltTextMain,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (hint != null) {
            Text(
                text = hint,
                fontFamily = HzFont.Mono,
                fontSize = 9.sp,
                color = VoltMutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 72.dp)
            )
        } else {
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = VoltMutedText, modifier = Modifier.size(14.dp))
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
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF0B0F0D) else Color.White
    val busy = installStatus.running
    val fg = if (busy) accent else onAccent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(140.dp)
            .height(38.dp)
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
                size = 15.dp,
                tint = fg
            )
            Text(
                text = if (installStatus.paused) "PAUSED"
                else if (installStatus.running) "${installStatus.percent}%"
                else if (installStatus.ready) "PLAY"
                else "INSTALL",
                fontFamily = if (busy) HzFont.Mono else null,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
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
            .height(38.dp)
            .animatedPressScale(onClick = onClick)
            .clip(HzShape.Control)
            .background(HzColor.Glass)
            .border(1.dp, HzColor.Hairline, HzShape.Control)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 14.dp, tint = VoltIconMuted)
        Text(text = label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = VoltTextMain, maxLines = 1)
    }
}

private fun hzStatusLine(s: AssetInstallStatus, playTime: String): String = when {
    s.ready -> if (playTime.isNotEmpty()) "Ready \u00B7 $playTime played" else "Ready"
    s.paused -> "Paused (${s.percent}%)"
    s.running -> "${s.percent}% \u00B7 ${s.speedText.ifBlank { "Downloading" }}"
    else -> "Needs download"
}

/**
 * Home: the wallpaper behind, the selected version and PLAY at the bottom left,
 * a short list of shortcuts on the right and a Boost chip top right. The rail is drawn by the shell.
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
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "HorizonR", fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            HzTag("NATIVE RUST")
        }

        var boostOn by remember { mutableStateOf(Boost.isOn(context)) }
        HzChip(
            label = if (boostOn) "Boost on" else "Boost",
            icon = Icons.Default.Bolt,
            active = boostOn,
            accent = accent,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            boostOn = !boostOn
            Boost.set(context, boostOn)
            android.widget.Toast.makeText(
                context,
                if (boostOn) "Boost on: memory freed, game runs at higher priority" else "Boost off",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HzShortcut("Mods", null, "ic_ui_mods", Icons.Default.Extension, 0) { onNavigate(LauncherNavTab.FILES) }
            HzShortcut("Shaders", null, "ic_ui_shaders", Icons.Default.WbSunny, 1) { onNavigate(LauncherNavTab.STORE) }
            HzShortcut("Worlds", null, "ic_ui_worlds", Icons.Default.Public, 2) {
                FilesNav.pendingFolder = "saves"
                onNavigate(LauncherNavTab.FILES)
            }
            HzShortcut("Servers", null, "ic_ui_servers", Icons.Default.Dns, 3) { onNavigate(LauncherNavTab.SERVERS) }
            HzShortcut("Skins", activeAccount.username, "ic_ui_skins", Icons.Default.Checkroom, 4) { onNavigate(LauncherNavTab.ACCOUNTS) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Crossfade(targetState = quote, animationSpec = tween(HzMotion.Medium), label = "HomeQuote") { q ->
                if (q != null) {
                    Text(
                        text = "\u201C$q\u201D",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.animatedPressScale(onClick = onQuoteTap)
                    )
                }
            }
            Text(
                text = "SELECTED VERSION",
                fontFamily = HzFont.Mono,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                letterSpacing = 1.sp,
                color = VoltMutedText,
                maxLines = 1
            )
            Text(
                text = "Minecraft 26.3",
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dot = when {
                    installStatus.ready -> Color(0xFF5FD068)
                    installStatus.paused -> Color(0xFFFFB74D)
                    installStatus.running -> accent
                    else -> VoltMutedText
                }
                Box(modifier = Modifier.size(6.dp).clip(HzShape.Chip).background(dot))
                Text(
                    text = hzStatusLine(installStatus, playTime),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (installStatus.running) {
                val bar by animateFloatAsState(
                    targetValue = (installStatus.percent / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(HzMotion.Medium, easing = HzMotion.Ease),
                    label = "HomeBar"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(HzShape.Chip)
                        .background(Color.White.copy(alpha = 0.14f))
                        .drawBehind { drawRect(color = accent, size = Size(size.width * bar, size.height)) }
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HzPlayButton(installStatus = installStatus, accent = accent, onLaunch = onLaunch)
                HzGhostButton("Game files", "ic_ui_folder", Icons.Default.Folder) { onNavigate(LauncherNavTab.VERSIONS) }
            }
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
