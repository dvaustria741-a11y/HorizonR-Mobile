package dev.horizon.android.controls.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val Slate = Color(0xFF394952)

// The overlay menu and layout editor use ZalithLauncher's dark grey and teal. The launcher's own
// setting cards pass their palette in through ControlsCardStyle, so they are not affected.
val ControlsColors = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFE6E6E6),
    secondary = Color(0xFF80CBC4),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFE6E6E6),
    background = Color(0xFF121212),
    surface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFB4B4B4),
    error = Color(0xFFFF8A80),
)

/** Colors of a setting card. The launcher passes its own (dynamic) palette so the cards match its other ones. */
data class ControlsCardStyle(
    val card: Color,
    val accent: Color,
    val onAccent: Color,
    val muted: Color,
    val track: Color = Color(0xFF555555),
    val row: Color = Color(0xFF2A3136),
    val capsule: Color = Color(0xFF394952),
)

private val DefaultCardStyle = ControlsCardStyle(
    card = ControlsColors.surface,
    accent = ControlsColors.primary,
    onAccent = ControlsColors.onPrimary,
    muted = ControlsColors.onSurfaceVariant,
)

val LocalControlsCardStyle = staticCompositionLocalOf { DefaultCardStyle }

@Composable
fun ControlsTheme(style: ControlsCardStyle? = null, content: @Composable () -> Unit) {
    val used = style ?: DefaultCardStyle
    val scheme = ControlsColors.copy(
        primary = used.accent,
        onPrimary = used.onAccent,
        surface = used.card,
        onSurface = Color.White,
        onSurfaceVariant = used.muted,
    )
    CompositionLocalProvider(LocalControlsCardStyle provides used) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Same background, shape and padding as the launcher's own setting cards. */
@Composable
fun ControlsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LocalControlsCardStyle.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun CardTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

fun fmt(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

fun stepsFor(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    (((range.endInclusive - range.start) / step).toInt() - 1).coerceAtLeast(0)

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    display: (Float) -> String,
    onDone: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(display(current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onDone(current) },
            valueRange = range,
            steps = stepsFor(range, step),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = LocalControlsCardStyle.current.track,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            )
        )
    }
}

@Composable
fun SettingSwitch(label: String, value: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    var current by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let { Hint(it) }
        }
        Switch(
            checked = current,
            onCheckedChange = { current = it; onChange(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            )
        )
    }
}

fun screenSize(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
    }
    return maxOf(w, h) to minOf(w, h)
}

val HzRoot = Color(0xFF14181B)
val HzCard = Color(0xFF22282C)
val HzRow = Color(0xFF2A3136)
val HzCapsule = Color(0xFF394952)
val HzAccent = Color(0xFF8BE0FF)
val HzOnAccent = Color(0xFF0E2430)
val HzMuted = Color(0xFF9EA8B0)

val HorizonCardStyle = ControlsCardStyle(
    card = HzRow,
    accent = HzAccent,
    onAccent = HzOnAccent,
    muted = HzMuted,
    track = HzCapsule,
)
