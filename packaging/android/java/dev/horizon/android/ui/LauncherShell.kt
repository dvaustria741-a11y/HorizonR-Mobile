package dev.horizon.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * Launcher shell: a floating icon dock on the left, a slim top bar (page name, install status, account, logs,
 * files), and every page except Home inside one rounded glass "window" over the theme wallpaper.
 * This replaces the old full-height text rail.
 */

internal val HzWindowShape = RoundedCornerShape(20.dp)

/** The window every page except Home sits in: rounded, translucent glass, hairline border. */
internal fun Modifier.hzWindow(): Modifier = this
    .clip(HzWindowShape)
    .background(HzColor.Glass.copy(alpha = 0.88f))
    .border(1.dp, HzColor.Hairline, HzWindowShape)

internal fun hzTabTitle(tab: LauncherNavTab): String = when (tab) {
    LauncherNavTab.HOME -> "Home"
    LauncherNavTab.VERSIONS -> "Game"
    LauncherNavTab.STORE -> "Store"
    LauncherNavTab.FILES -> "Mods & files"
    LauncherNavTab.SERVERS -> "Servers"
    LauncherNavTab.ACCOUNTS -> "Skins"
    LauncherNavTab.LOGS -> "Logs"
    LauncherNavTab.SETTINGS -> "Settings"
}

/** One still wallpaper behind the whole launcher, dimmed so the glass panels stay readable. */
@Composable
internal fun HzBackdrop() {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.hz_purple_wall),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(HzColor.Background.copy(alpha = 0.74f), HzColor.Background.copy(alpha = 0.92f))
                )
            )
        )
    }
}

private data class HzDockEntry(val tab: LauncherNavTab, val label: String, val icon: String, val fallback: ImageVector)

private val DockShape = RoundedCornerShape(22.dp)

@Composable
internal fun HzDock(
    selectedTab: LauncherNavTab,
    installStatus: AssetInstallStatus,
    onSelectTab: (LauncherNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = remember {
        listOf(
            HzDockEntry(LauncherNavTab.HOME, "Home", "ic_ui_none", Icons.Default.Home),
            HzDockEntry(LauncherNavTab.VERSIONS, "Game", "ic_ui_game", Icons.Default.Download),
            HzDockEntry(LauncherNavTab.STORE, "Store", "ic_ui_store", Icons.Default.Storefront),
            HzDockEntry(LauncherNavTab.FILES, "Mods", "ic_ui_mods", Icons.Default.Extension),
            HzDockEntry(LauncherNavTab.SERVERS, "Servers", "ic_ui_servers", Icons.Default.Dns),
            HzDockEntry(LauncherNavTab.ACCOUNTS, "Skins", "ic_ui_skins", Icons.Default.Checkroom),
            HzDockEntry(LauncherNavTab.SETTINGS, "Settings", "ic_ui_settings", Icons.Default.Settings)
        )
    }
    Column(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
            )
            .padding(start = 8.dp, top = 8.dp, bottom = 10.dp)
            .width(56.dp)
            .fillMaxHeight()
            .clip(DockShape)
            .background(HzColor.Glass)
            .border(1.dp, HzColor.Hairline, DockShape)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        HzBrandCube(size = 26.dp)
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            entries.forEach { e ->
                HzDockButton(
                    entry = e,
                    selected = e.tab == selectedTab,
                    busy = e.tab == LauncherNavTab.VERSIONS && installStatus.running,
                    onClick = { onSelectTab(e.tab) }
                )
            }
        }
    }
}

@Composable
private fun HzDockButton(entry: HzDockEntry, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        targetValue = if (selected) Color.White else HzColor.TextMuted,
        animationSpec = tween(HzMotion.Short),
        label = "DockTint"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = entry.label }
            .animatedPressScale(pressedScale = 0.92f, onClick = onClick)
            .clip(HzShape.Control)
            .then(
                if (selected) Modifier
                    .background(Brush.linearGradient(listOf(HzColor.Violet.copy(alpha = 0.55f), HzColor.AccentSecondary.copy(alpha = 0.40f))))
                    .border(1.dp, HzColor.AccentLine, HzShape.Control)
                else Modifier
            )
    ) {
        VoltIcon(name = entry.icon, fallback = entry.fallback, size = 21.dp, tint = tint)
        if (busy) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(HzColor.AccentSecondary)
            )
        }
    }
}

@Composable
internal fun HzTopBar(
    tab: LauncherNavTab,
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    installStatus: AssetInstallStatus,
    onOpenAccount: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().height(46.dp).padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HzSpace.S)
    ) {
        Text(
            text = hzTabTitle(tab),
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = HzColor.TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        val dot = when {
            installStatus.ready -> HzColor.Success
            installStatus.paused -> HzColor.Warning
            installStatus.running -> HzColor.Accent
            else -> HzColor.TextMuted
        }
        HzStatusPill(text = hzStatusLine(installStatus, ""), color = dot)
        Row(
            modifier = Modifier
                .height(34.dp)
                .animatedPressScale(pressedScale = 0.97f, onClick = onOpenAccount)
                .clip(RoundedCornerShape(50))
                .background(HzColor.Glass)
                .border(1.dp, HzColor.Hairline, RoundedCornerShape(50))
                .padding(start = 5.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 24.dp, cornerRadius = 12.dp)
            Text(
                text = activeAccount.username,
                fontSize = HzText.Body.sp,
                fontWeight = FontWeight.SemiBold,
                color = HzColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp)
            )
        }
        HzBarIconButton("ic_ui_logs", Icons.Default.Terminal, "Logs", tab == LauncherNavTab.LOGS, onOpenLogs)
        HzBarIconButton("ic_ui_folder", Icons.Default.Folder, "Open files", false, onOpenFolder)
    }
}

@Composable
private fun HzBarIconButton(name: String, fallback: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .semantics { contentDescription = description }
            .animatedPressScale(pressedScale = 0.94f, onClick = onClick)
            .clip(HzShape.Control)
            .background(if (selected) HzColor.AccentSoft else HzColor.Glass)
            .border(1.dp, if (selected) HzColor.AccentLine else HzColor.Hairline, HzShape.Control)
    ) {
        VoltIcon(name = name, fallback = fallback, size = 17.dp, tint = if (selected) HzColor.Accent else HzColor.TextIcon)
    }
}

/** One figure with a small label above it: used in rows of stats instead of a spec table. */
@Composable
internal fun HzStatTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(HzShape.Control)
            .background(HzColor.PanelLow.copy(alpha = 0.85f))
            .border(1.dp, HzColor.Hairline, HzShape.Control)
            .padding(horizontal = HzSpace.M, vertical = HzSpace.S),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = label, fontSize = HzText.Micro.sp, lineHeight = 12.sp, color = HzColor.TextMuted, maxLines = 1)
        Text(
            text = value,
            fontFamily = HzFont.Mono,
            fontSize = HzText.Body.sp,
            lineHeight = 16.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
