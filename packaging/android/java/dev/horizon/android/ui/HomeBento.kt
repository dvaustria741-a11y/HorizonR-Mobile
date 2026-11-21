package dev.horizon.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.R
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

/**
 * Home as a bento grid, like the RussoViller theme preview: a wide hero card (video) with the selected version,
 * its status and PLAY; a tall portrait card (video) that opens the wardrobe; and a row of four quick tiles.
 * Only two videos play at once; the page behind is the still backdrop drawn by the shell.
 */
@Composable
internal fun HzHomeBento(
    accent: Color,
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    installStatus: AssetInstallStatus,
    quote: String?,
    onQuoteTap: () -> Unit,
    onNavigate: (LauncherNavTab) -> Unit,
    onOpenFolder: () -> Unit,
    onLaunch: () -> Unit,
    launchProgress: Float? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(HzSpace.M)
    ) {
        Column(
            modifier = Modifier.weight(1.8f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(HzSpace.M)
        ) {
            HeroCard(
                accent = accent,
                installStatus = installStatus,
                quote = quote,
                onQuoteTap = onQuoteTap,
                onGameFiles = { onNavigate(LauncherNavTab.VERSIONS) },
                onLaunch = onLaunch,
                launchProgress = launchProgress,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(76.dp),
                horizontalArrangement = Arrangement.spacedBy(HzSpace.M)
            ) {
                BentoTile("Mods", "Installed and enabled", "ic_ui_mods", Icons.Default.Extension, 0, Modifier.weight(1f)) { onNavigate(LauncherNavTab.FILES) }
                BentoTile("Shaders", "Browse packs", "ic_ui_shaders", Icons.Default.WbSunny, 1, Modifier.weight(1f)) { onNavigate(LauncherNavTab.STORE) }
                BentoTile("Worlds", "Your saves", "ic_ui_worlds", Icons.Default.Public, 2, Modifier.weight(1f)) {
                    FilesNav.pendingFolder = "saves"
                    onNavigate(LauncherNavTab.FILES)
                }
                BentoTile("Servers", "Play online", "ic_ui_servers", Icons.Default.Dns, 3, Modifier.weight(1f)) { onNavigate(LauncherNavTab.SERVERS) }
            }
        }
        PortraitCard(
            activeAccount = activeAccount,
            activeSkinBitmap = activeSkinBitmap,
            onOpen = { onNavigate(LauncherNavTab.ACCOUNTS) },
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun HeroCard(
    accent: Color,
    installStatus: AssetInstallStatus,
    quote: String?,
    onQuoteTap: () -> Unit,
    onGameFiles: () -> Unit,
    onLaunch: () -> Unit,
    launchProgress: Float?,
    modifier: Modifier = Modifier
) {
    val playTime = PlayTime.label(LocalContext.current)
    Box(
        modifier = modifier
            .clip(HzShape.Panel)
            .border(1.dp, HzGlassRim, HzShape.Panel)
    ) {
        HzVideoBackground(video = R.raw.hz_hero, still = R.drawable.hz_purple_hero, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.30f to Color.Transparent, 1f to HzColor.Background.copy(alpha = 0.94f))
            )
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(HzSpace.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Crossfade(targetState = quote, animationSpec = tween(HzMotion.Medium), label = "HomeQuote") { q ->
                    if (q != null) {
                        Text(
                            text = "\u201C$q\u201D",
                            fontSize = HzText.Caption.sp,
                            lineHeight = 14.sp,
                            fontStyle = FontStyle.Italic,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.animatedPressScale(onClick = onQuoteTap)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .animatedPressScale(onClick = onGameFiles)
                    .hzGlass(HzShape.Control, darkness = 0.38f)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VoltIcon(name = "ic_ui_folder", fallback = Icons.Default.Folder, size = 16.dp, tint = HzColor.TextIcon)
                Text(text = "Game files", fontSize = HzText.Body.sp, fontWeight = FontWeight.SemiBold, color = HzColor.TextPrimary, maxLines = 1)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(HzSpace.M),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(HzSpace.M)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HzSpace.XS)) {
                Text(
                    text = "Minecraft 26.3",
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HzSpace.S)) {
                    HzTag("NATIVE RUST")
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
            HzPlayButton(installStatus = installStatus, accent = accent, onLaunch = onLaunch, launchProgress = launchProgress)
        }
    }
}

@Composable
private fun PortraitCard(
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .animatedPressScale(pressedScale = 0.985f, onClick = onOpen)
            .clip(HzShape.Panel)
            .border(1.dp, HzGlassRim, HzShape.Panel)
    ) {
        HzVideoBackground(video = R.raw.hz_portrait, still = R.drawable.hz_purple_portrait, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to HzColor.Background.copy(alpha = 0.92f))
            )
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(HzSpace.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
        ) {
            MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 34.dp, cornerRadius = 10.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeAccount.username,
                    fontSize = HzText.Section.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Open wardrobe",
                    fontFamily = HzFont.Mono,
                    fontSize = HzText.Micro.sp,
                    lineHeight = 12.sp,
                    color = VoltMutedText,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BentoTile(
    label: String,
    hint: String,
    iconName: String,
    fallback: ImageVector,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + index * 40L)
        enter.animateTo(1f, tween(HzMotion.Medium, easing = HzMotion.Ease))
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 8.dp.toPx()
            }
            .animatedPressScale(pressedScale = 0.96f, onClick = onClick)
            .hzGlass(HzShape.Panel, darkness = 0.30f)
            .padding(horizontal = HzSpace.M, vertical = HzSpace.S),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        VoltIcon(name = iconName, fallback = fallback, size = 22.dp, tint = HzColor.Accent)
        Column {
            Text(text = label, fontSize = HzText.Section.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = HzColor.TextPrimary, maxLines = 1)
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
