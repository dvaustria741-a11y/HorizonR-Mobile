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

/** Row of quick tiles shown on the Home screen. Original layout: a compact horizontal deck, not stacked bars. */
@Composable
internal fun VoltHomeDeck(onNavigate: (LauncherNavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VoltTile("Game", "Install & files", "ic_ui_game", Icons.Default.Download, 0, { onNavigate(LauncherNavTab.VERSIONS) }, Modifier.width(96.dp))
        VoltTile("Mods", "Packs & shaders", "ic_ui_mods", Icons.Default.Extension, 1, { onNavigate(LauncherNavTab.FILES) }, Modifier.width(96.dp))
        VoltTile("Skins", "Wardrobe", "ic_ui_skins", Icons.Default.Checkroom, 2, { onNavigate(LauncherNavTab.ACCOUNTS) }, Modifier.width(96.dp))
        VoltTile("Servers", "Quick join", "ic_ui_servers", Icons.Default.Dns, 3, { onNavigate(LauncherNavTab.SERVERS) }, Modifier.width(96.dp))
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
