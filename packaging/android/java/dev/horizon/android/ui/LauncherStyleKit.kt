package dev.horizon.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.model.LauncherNavTab
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.model.LauncherAccount
import dev.horizon.android.ui.theme.HzPixel
import dev.horizon.android.ui.theme.HzRadius
import kotlinx.coroutines.delay

/** Volt-style neutral surfaces: dark gray cards, a hairline stroke and a faint top highlight. */
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

/** Square dark gray card: icon on top, title and subtitle below. Slides up and fades in, staggered by [index]. */
@Composable
internal fun VoltTile(
    title: String,
    subtitle: String,
    iconName: String,
    fallback: ImageVector,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80L + index * 70L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "VoltTileIn"
    )
    val shape = RoundedCornerShape(HzRadius.card)
    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24.dp.toPx()
            }
            .animatedPressScale(onClick = onClick)
            .clip(shape)
            .background(VoltGrayCard.copy(alpha = 0.92f))
            .border(1.dp, VoltGrayStroke, shape)
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.10f),
                    start = Offset(18.dp.toPx(), 0.5f),
                    end = Offset(size.width - 18.dp.toPx(), 0.5f),
                    strokeWidth = 1.5f
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 24.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1
        )
        Text(
            text = subtitle,
            fontSize = 10.sp,
            color = VoltMutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
    LauncherNavTab.HOME -> "ic_ui_home"
    else -> "ic_ui_none"
}

private data class VoltNavEntry(val tab: LauncherNavTab, val label: String, val icon: String, val fallback: ImageVector)

private fun Modifier.voltSurface(shape: androidx.compose.ui.graphics.Shape, alpha: Float = 0.92f): Modifier =
    this
        .clip(shape)
        .background(if (alpha >= 0.93f) VoltGrayCard else VoltGlass)
        .border(1.dp, VoltGrayStroke, shape)

/**
 * Pixel-art button plate: the left and right caps keep their proportions (no squashed corners),
 * the flat middle stretches to any width. Falls back to a dark gray card if the PNG is missing.
 */
@Composable
private fun voltHasDrawable(name: String): Boolean {
    val context = LocalContext.current
    return remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) != 0 }
}

@Composable
private fun Modifier.voltPlate(name: String, fallbackShape: androidx.compose.ui.graphics.Shape): Modifier {
    val context = LocalContext.current
    val resId = remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
    if (resId == 0) return this.voltSurface(fallbackShape)
    val bmp = ImageBitmap.imageResource(id = resId)
    return this.drawBehind {
        val w = size.width.toInt()
        val h = size.height.toInt()
        val sw = bmp.width
        val sh = bmp.height
        val scale = h.toFloat() / sh
        val capSrc = (sh * 0.7f).toInt().coerceAtMost(sw / 3)
        val capDst = (capSrc * scale).toInt().coerceAtMost(w / 2)
        drawImage(
            image = bmp,
            srcOffset = IntOffset(0, 0), srcSize = IntSize(capSrc, sh),
            dstOffset = IntOffset(0, 0), dstSize = IntSize(capDst, h),
            filterQuality = FilterQuality.Medium
        )
        drawImage(
            image = bmp,
            srcOffset = IntOffset(capSrc, 0), srcSize = IntSize(sw - 2 * capSrc, sh),
            dstOffset = IntOffset(capDst, 0), dstSize = IntSize(w - 2 * capDst, h),
            filterQuality = FilterQuality.Medium
        )
        drawImage(
            image = bmp,
            srcOffset = IntOffset(sw - capSrc, 0), srcSize = IntSize(capSrc, sh),
            dstOffset = IntOffset(w - capDst, 0), dstSize = IntSize(capDst, h),
            filterQuality = FilterQuality.Medium
        )
    }
}

@Composable
private fun VoltCircleButton(name: String, fallback: ImageVector, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .animatedPressScale(onClick = onClick)
            .voltSurface(RoundedCornerShape(HzRadius.control))
    ) {
        VoltIcon(name = name, fallback = fallback, size = 17.dp)
    }
}

/** Volt-style bottom bar for every screen except Home: account chip, tab pill, quick buttons. */
@Composable
internal fun VoltBottomNav(
    selectedTab: LauncherNavTab,
    installStatus: AssetInstallStatus,
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    accent: Color,
    onSelectTab: (LauncherNavTab) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val entries = remember {
        listOf(
            VoltNavEntry(LauncherNavTab.HOME, "Home", "ic_ui_home", Icons.Default.Home),
            VoltNavEntry(LauncherNavTab.VERSIONS, "Game", "ic_ui_game", Icons.Default.Download),
            VoltNavEntry(LauncherNavTab.STORE, "Store", "ic_ui_store", Icons.Default.Storefront),
            VoltNavEntry(LauncherNavTab.FILES, "Mods", "ic_ui_mods", Icons.Default.Extension),
            VoltNavEntry(LauncherNavTab.SERVERS, "Servers", "ic_ui_servers", Icons.Default.Dns),
            VoltNavEntry(LauncherNavTab.ACCOUNTS, "Skins", "ic_ui_skins", Icons.Default.Checkroom),
            VoltNavEntry(LauncherNavTab.SETTINGS, "Settings", "ic_ui_settings", Icons.Default.Settings)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .animatedPressScale(onClick = onOpenAccount)
                .voltSurface(RoundedCornerShape(HzRadius.card))
                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 28.dp, cornerRadius = 7.dp)
            Column {
                Text(
                    text = activeAccount.username,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 80.dp)
                )
                Text(
                    text = activeAccount.accountType.badge,
                    fontSize = 9.sp,
                    color = accent,
                    maxLines = 1
                )
            }
        }

        Row(
            modifier = Modifier
                .voltSurface(RoundedCornerShape(HzRadius.card))
                .padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            entries.forEach { e ->
                val selected = e.tab == selectedTab
                val bg by animateColorAsState(
                    targetValue = if (selected) Color(0xFF2A2F3C) else Color.Transparent,
                    animationSpec = tween(240),
                    label = "VoltNavBg"
                )
                val tint by animateColorAsState(
                    targetValue = if (selected) accent else VoltMutedText,
                    animationSpec = tween(240),
                    label = "VoltNavTint"
                )
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(HzRadius.control))
                        .background(bg)
                        .animatedPressScale { onSelectTab(e.tab) }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    VoltIcon(name = e.icon, fallback = e.fallback, size = 17.dp, tint = tint)
                    Text(
                        text = if (e.tab == LauncherNavTab.VERSIONS && installStatus.running) "${installStatus.percent}%" else e.label,
                        fontSize = 8.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VoltCircleButton("ic_ui_folder", Icons.Default.Folder, onOpenFolder)
            VoltCircleButton("ic_ui_logs", Icons.Default.Terminal, onOpenLogs)
        }
    }
}

@Composable
private fun VoltBigButton(
    title: String,
    subtitle: String,
    plate: String,
    iconName: String,
    fallback: ImageVector,
    index: Int,
    onClick: () -> Unit
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60L + index * 80L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "VoltBigIn"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .graphicsLayer {
                alpha = progress
                translationX = -(1f - progress) * 32.dp.toPx()
            }
            .animatedPressScale(onClick = onClick)
            .voltPlate(plate, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Volt draws the page icon straight on the slab (no chip), white at ~92% opacity.
        VoltIcon(name = iconName, fallback = fallback, size = 20.dp, tint = Color.White.copy(alpha = 0.92f))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1
            )
            Text(
                text = subtitle,
                fontSize = 8.5.sp,
                lineHeight = 10.sp,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Volt's right-rail chip (Worlds / Servers / Replays...): glass fill, 1px glass border, muted icon, main-text label. */
@Composable
private fun VoltSidePill(label: String, iconName: String, fallback: ImageVector, index: Int, active: Boolean = false, onClick: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200L + index * 70L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "VoltSideIn"
    )
    Row(
        modifier = Modifier
            .width(84.dp)
            .height(23.dp)
            .graphicsLayer {
                alpha = progress
                translationX = (1f - progress) * 28.dp.toPx()
            }
            .animatedPressScale(onClick = onClick)
            .voltSurface(RoundedCornerShape(HzRadius.chip), 0.8f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 13.dp, tint = if (active) Color(0xFF79E59C) else VoltIconMuted)
        Text(
            text = label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            color = VoltTextMain,
            maxLines = 1
        )
    }
}

@Composable
private fun VoltSquareButton(iconName: String, fallback: ImageVector, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .animatedPressScale(onClick = onClick)
            .voltSurface(RoundedCornerShape(10.dp), 0.8f)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 17.dp, tint = VoltTextMain)
    }
}

@Composable
private fun VoltPlayBlock(installStatus: AssetInstallStatus, accent: Color, playTime: String, onLaunch: () -> Unit, onFiles: () -> Unit, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = (installStatus.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "VoltPlayProgress"
    )
    val green = Color(0xFF5FD068)
    val darkGreen = Color(0xFF2F9E44)
    val content = if (installStatus.running) accent else Color(0xFF1F3D0A)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val plateModifier = if (installStatus.running) {
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(VoltGrayRow)
        } else {
            Modifier.voltPlate("bg_ui_play", RoundedCornerShape(14.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .animatedPressScale(onClick = onLaunch)
                .then(plateModifier)
                .drawBehind {
                    if (installStatus.running) {
                        drawRect(color = accent.copy(alpha = 0.30f), size = Size(size.width * progress, size.height))
                    }
                }
                .padding(bottom = 4.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoltIcon(
                        name = if (installStatus.ready) "ic_ui_play" else "ic_ui_download",
                        fallback = if (installStatus.ready) Icons.Default.PlayArrow else Icons.Default.Download,
                        size = 16.dp,
                        tint = content
                    )
                    Text(
                        text = if (installStatus.paused) "PAUSED"
                        else if (installStatus.running) "${installStatus.percent}%"
                        else if (installStatus.ready) "PLAY"
                        else "INSTALL",
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = content
                    )
                }
                if (installStatus.ready && playTime.isNotEmpty()) {
                    Text(text = playTime, fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2F6B12), maxLines = 1)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .animatedPressScale(onClick = onFiles)
                .voltPlate("bg_ui_files", RoundedCornerShape(9.dp))
                .padding(bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoltIcon(name = "ic_ui_folder", fallback = Icons.Default.Folder, size = 9.dp, tint = Color(0xFF4A3905))
            Text(text = "GAME FILES", fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4A3905), maxLines = 1)
        }
    }
}

/** One-shot hint for the Files page: which folder tab to open first (null = Mods). */
internal object FilesNav {
    @JvmField var pendingFolder: String? = null
}

/**
 * Volt-style Home layer: brand top-left, stacked cards under it, square icon buttons top-right,
 * a pill list below them, and the player / version / PLAY row along the bottom.
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
    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.displayCutout)).padding(14.dp)) {
        Column(modifier = Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MinecraftGrassCubeIcon(size = 26.dp)
                Column {
                    Text(text = "HORIZONR", fontFamily = HzPixel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Text(text = "Minecraft 26.3 \u00B7 Native Rust", fontSize = 9.sp, color = VoltMutedText, maxLines = 1)
                }
            }
            if (quote != null) {
                Text(
                    text = "\u201C$quote\u201D",
                    fontSize = 9.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFFFFE082),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .animatedPressScale(onClick = onQuoteTap)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 52.dp)
                .width(176.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VoltBigButton("MODS", "Mods & packs", "bg_ui_mods", "ic_ui_mods", Icons.Default.Extension, 0) { onNavigate(LauncherNavTab.FILES) }
            VoltBigButton("MY SKIN", activeAccount.username, "bg_ui_skin", "ic_ui_skins", Icons.Default.Checkroom, 1) { onNavigate(LauncherNavTab.ACCOUNTS) }
            VoltBigButton("SHADERS", "Shader packs", "bg_ui_shaders", "ic_ui_shaders", Icons.Default.WbSunny, 2) { onNavigate(LauncherNavTab.STORE) }
        }

        Row(modifier = Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VoltSquareButton("ic_ui_game", Icons.Default.Download) { onNavigate(LauncherNavTab.VERSIONS) }
            VoltSquareButton("ic_ui_logs", Icons.Default.Terminal) { onNavigate(LauncherNavTab.LOGS) }
            VoltSquareButton("ic_ui_folder", Icons.Default.Folder) { onOpenFolder() }
            VoltSquareButton("ic_ui_settings", Icons.Default.Settings) { onNavigate(LauncherNavTab.SETTINGS) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 44.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VoltSidePill("Servers", "ic_ui_servers", Icons.Default.Dns, 0) { onNavigate(LauncherNavTab.SERVERS) }
            VoltSidePill("Store", "ic_ui_store", Icons.Default.Storefront, 1) { onNavigate(LauncherNavTab.STORE) }
            VoltSidePill("Worlds", "ic_ui_worlds", Icons.Default.Public, 2) {
                FilesNav.pendingFolder = "saves"
                onNavigate(LauncherNavTab.FILES)
            }
            val boostContext = androidx.compose.ui.platform.LocalContext.current
            var boostOn by remember { mutableStateOf(Boost.isOn(boostContext)) }
            VoltSidePill(if (boostOn) "Boost ON" else "Boost", "ic_ui_boost", Icons.Default.Bolt, 3, active = boostOn) {
                boostOn = !boostOn
                Boost.set(boostContext, boostOn)
                android.widget.Toast.makeText(boostContext, if (boostOn) "Boost on: memory freed, game runs at higher priority" else "Boost off", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        val playTime = PlayTime.label(androidx.compose.ui.platform.LocalContext.current)
        val hasVersionPlate = voltHasDrawable("bg_ui_version")
        val versionMuted = if (hasVersionPlate) Color.White.copy(alpha = 0.78f) else VoltMutedText
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .animatedPressScale { onNavigate(LauncherNavTab.ACCOUNTS) }
                    .voltSurface(RoundedCornerShape(HzRadius.card), 0.8f)
                    .padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 24.dp, cornerRadius = 6.dp)
                Text(
                    text = "${activeAccount.username} \u2022 ${activeAccount.accountType.badge}",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VoltTextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp)
                )
            }

            Row(
                modifier = Modifier.align(Alignment.BottomCenter),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .width(204.dp)
                        .height(58.dp)
                        .animatedPressScale { onNavigate(LauncherNavTab.VERSIONS) }
                        .voltPlate("bg_ui_version", RoundedCornerShape(16.dp))
                        .padding(start = 12.dp, end = 10.dp, bottom = if (hasVersionPlate) 4.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF12141C).copy(alpha = 0.85f))
                    ) {
                        MinecraftGrassCubeIcon(size = 25.dp)
                    }
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(text = "SELECTED VERSION", fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = versionMuted, maxLines = 1)
                        Text(text = "Minecraft 26.3", fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                        Text(
                            text = if (installStatus.ready) (if (playTime.isNotEmpty()) "Ready \u2022 $playTime played" else "Ready")
                            else if (installStatus.paused) "Paused (${installStatus.percent}%)"
                            else if (installStatus.running) "${installStatus.percent}% (${installStatus.speedText.ifBlank { "Downloading" }})"
                            else "Needs download",
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            color = if (hasVersionPlate) Color.White.copy(alpha = 0.9f)
                            else if (installStatus.ready) Color(0xFF81C784)
                            else if (installStatus.paused) Color(0xFFFFB74D)
                            else accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                VoltPlayBlock(
                    installStatus = installStatus,
                    accent = accent,
                    playTime = playTime,
                    onLaunch = onLaunch,
                    onFiles = { onNavigate(LauncherNavTab.VERSIONS) },
                    modifier = Modifier.width(124.dp)
                )
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
