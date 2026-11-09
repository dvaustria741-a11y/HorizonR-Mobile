package dev.horizon.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.model.LauncherAccount
import kotlinx.coroutines.delay

/** Volt-style neutral surfaces: dark gray cards, a hairline stroke and a faint top highlight. */
internal val VoltGrayRoot = Color(0xFF0D0F16)
internal val VoltGrayCard = Color(0xFF171A23)
internal val VoltGrayRow = Color(0xFF1F232E)
internal val VoltGrayStroke = Color(0xFF2B303C)
internal val VoltMutedText = Color(0xFF9AA1AF)

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
    val shape = RoundedCornerShape(18.dp)
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
    else -> "ic_ui_none"
}

private data class VoltNavEntry(val tab: LauncherNavTab, val label: String, val icon: String, val fallback: ImageVector)

private fun Modifier.voltSurface(shape: androidx.compose.ui.graphics.Shape, alpha: Float = 0.92f): Modifier =
    this
        .clip(shape)
        .background(VoltGrayCard.copy(alpha = alpha))
        .border(1.dp, VoltGrayStroke, shape)

@Composable
private fun VoltCircleButton(name: String, fallback: ImageVector, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .animatedPressScale(onClick = onClick)
            .voltSurface(CircleShape)
    ) {
        VoltIcon(name = name, fallback = fallback, size = 20.dp)
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
            VoltNavEntry(LauncherNavTab.HOME, "Home", "ic_ui_none", Icons.Default.Home),
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
            .height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .animatedPressScale(onClick = onOpenAccount)
                .voltSurface(RoundedCornerShape(30.dp))
                .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 34.dp, cornerRadius = 17.dp)
            Column {
                Text(
                    text = activeAccount.username,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 90.dp)
                )
                Text(
                    text = activeAccount.accountType.badge,
                    fontSize = 10.sp,
                    color = accent,
                    maxLines = 1
                )
            }
        }

        Row(
            modifier = Modifier
                .voltSurface(RoundedCornerShape(30.dp))
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
                        .clip(RoundedCornerShape(22.dp))
                        .background(bg)
                        .animatedPressScale { onSelectTab(e.tab) }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    VoltIcon(name = e.icon, fallback = e.fallback, size = 20.dp, tint = tint)
                    Text(
                        text = if (e.tab == LauncherNavTab.VERSIONS && installStatus.running) "${installStatus.percent}%" else e.label,
                        fontSize = 9.sp,
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
    tint: Color,
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
            .height(56.dp)
            .graphicsLayer {
                alpha = progress
                translationX = -(1f - progress) * 32.dp.toPx()
            }
            .animatedPressScale(onClick = onClick)
            .voltSurface(RoundedCornerShape(16.dp), 0.94f)
            .drawBehind {
                drawRect(
                    color = tint,
                    topLeft = Offset(0f, size.height - 3.dp.toPx()),
                    size = Size(size.width, 3.dp.toPx())
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.10f),
                    start = Offset(16.dp.toPx(), 0.5f),
                    end = Offset(size.width - 16.dp.toPx(), 0.5f),
                    strokeWidth = 1.5f
                )
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint)
        ) {
            VoltIcon(name = iconName, fallback = fallback, size = 22.dp)
        }
        Column(verticalArrangement = Arrangement.Center) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            Text(text = subtitle, fontSize = 11.sp, color = VoltMutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VoltSidePill(label: String, iconName: String, fallback: ImageVector, index: Int, onClick: () -> Unit) {
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
            .width(124.dp)
            .height(34.dp)
            .graphicsLayer {
                alpha = progress
                translationX = (1f - progress) * 28.dp.toPx()
            }
            .animatedPressScale(onClick = onClick)
            .voltSurface(RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 18.dp)
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun VoltSquareButton(iconName: String, fallback: ImageVector, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .animatedPressScale(onClick = onClick)
            .voltSurface(RoundedCornerShape(12.dp))
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 21.dp)
    }
}

@Composable
private fun VoltPlayBlock(installStatus: AssetInstallStatus, accent: Color, onLaunch: () -> Unit, onFiles: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "VoltPlayPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "VoltPlayGlow"
    )
    val progress by animateFloatAsState(
        targetValue = (installStatus.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "VoltPlayProgress"
    )
    val green = Color(0xFF5FD068)
    val darkGreen = Color(0xFF2F9E44)
    val content = if (installStatus.running) accent else Color(0xFF0B3A14)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .drawBehind {
                    if (!installStatus.running) {
                        drawRoundRect(
                            color = green.copy(alpha = glow * 0.35f),
                            topLeft = Offset(-4.dp.toPx(), -2.dp.toPx()),
                            size = Size(size.width + 8.dp.toPx(), size.height + 4.dp.toPx()),
                            cornerRadius = CornerRadius(18.dp.toPx())
                        )
                    }
                }
                .animatedPressScale(onClick = onLaunch)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (installStatus.running) Brush.verticalGradient(listOf(VoltGrayRow, VoltGrayRow))
                    else Brush.verticalGradient(listOf(green, darkGreen))
                )
                .drawBehind {
                    if (installStatus.running) {
                        drawRect(color = accent.copy(alpha = 0.30f), size = Size(size.width * progress, size.height))
                    }
                    drawLine(
                        color = Color.White.copy(alpha = 0.35f),
                        start = Offset(14.dp.toPx(), 1f),
                        end = Offset(size.width - 14.dp.toPx(), 1f),
                        strokeWidth = 2f
                    )
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VoltIcon(
                    name = if (installStatus.ready) "ic_ui_play" else "ic_ui_download",
                    fallback = if (installStatus.ready) Icons.Default.PlayArrow else Icons.Default.Download,
                    size = 20.dp,
                    tint = content
                )
                Text(
                    text = if (installStatus.paused) "PAUSED"
                    else if (installStatus.running) "${installStatus.percent}%"
                    else if (installStatus.ready) "PLAY"
                    else "INSTALL",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = content
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .animatedPressScale(onClick = onFiles)
                .voltSurface(RoundedCornerShape(9.dp)),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoltIcon(name = "ic_ui_folder", fallback = Icons.Default.Folder, size = 11.dp)
            Text(text = "GAME FILES", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VoltMutedText, maxLines = 1)
        }
    }
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
    Box(modifier = modifier.fillMaxSize().padding(14.dp)) {
        Column(modifier = Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MinecraftGrassCubeIcon(size = 34.dp)
                Column {
                    Text(text = "HORIZONR", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                    Text(text = "Minecraft 26.3 \u00B7 Native Rust", fontSize = 11.sp, color = VoltMutedText, maxLines = 1)
                }
            }
            if (quote != null) {
                Text(
                    text = "\u201C$quote\u201D",
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFFFFE082),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .animatedPressScale(onClick = onQuoteTap)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 66.dp)
                .width(236.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VoltBigButton("MODS", "Mods & packs", Color(0xFFE0455F), "ic_ui_mods", Icons.Default.Extension, 0) { onNavigate(LauncherNavTab.FILES) }
            VoltBigButton("MY SKIN", "Skin studio", Color(0xFF3F7DF0), "ic_ui_skins", Icons.Default.Checkroom, 1) { onNavigate(LauncherNavTab.ACCOUNTS) }
            VoltBigButton("SHADERS", "Browse shader packs", Color(0xFF4CB85A), "ic_ui_shaders", Icons.Default.WbSunny, 2) { onNavigate(LauncherNavTab.STORE) }
        }

        Row(modifier = Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoltSquareButton("ic_ui_game", Icons.Default.Download) { onNavigate(LauncherNavTab.VERSIONS) }
            VoltSquareButton("ic_ui_logs", Icons.Default.Terminal) { onNavigate(LauncherNavTab.LOGS) }
            VoltSquareButton("ic_ui_folder", Icons.Default.Folder) { onOpenFolder() }
            VoltSquareButton("ic_ui_settings", Icons.Default.Settings) { onNavigate(LauncherNavTab.SETTINGS) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 50.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VoltSidePill("Servers", "ic_ui_servers", Icons.Default.Dns, 0) { onNavigate(LauncherNavTab.SERVERS) }
            VoltSidePill("Store", "ic_ui_store", Icons.Default.Storefront, 1) { onNavigate(LauncherNavTab.STORE) }
            VoltSidePill("Worlds", "ic_ui_worlds", Icons.Default.Public, 2) { onNavigate(LauncherNavTab.FILES) }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .animatedPressScale { onNavigate(LauncherNavTab.ACCOUNTS) }
                    .voltSurface(RoundedCornerShape(30.dp))
                    .padding(start = 7.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 30.dp, cornerRadius = 15.dp)
                Text(
                    text = "${activeAccount.username} \u2022 ${activeAccount.accountType.badge}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(62.dp)
                    .animatedPressScale { onNavigate(LauncherNavTab.VERSIONS) }
                    .voltSurface(RoundedCornerShape(16.dp), 0.94f)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MinecraftGrassCubeIcon(size = 34.dp)
                Column(verticalArrangement = Arrangement.Center) {
                    Text(text = "SELECTED VERSION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VoltMutedText, maxLines = 1)
                    Text(text = "Minecraft 26.3", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                    Text(
                        text = if (installStatus.ready) "Ready"
                        else if (installStatus.paused) "Paused (${installStatus.percent}%)"
                        else if (installStatus.running) "${installStatus.percent}% (${installStatus.speedText.ifBlank { "Downloading" }})"
                        else "Needs download",
                        fontSize = 11.sp,
                        color = if (installStatus.ready) Color(0xFF81C784) else if (installStatus.paused) Color(0xFFFFB74D) else accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            VoltPlayBlock(
                installStatus = installStatus,
                accent = accent,
                onLaunch = onLaunch,
                onFiles = { onNavigate(LauncherNavTab.VERSIONS) },
                modifier = Modifier.width(150.dp)
            )
        }
    }
}
