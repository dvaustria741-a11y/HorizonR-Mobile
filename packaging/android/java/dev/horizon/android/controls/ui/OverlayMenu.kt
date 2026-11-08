package dev.horizon.android.controls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.horizon.android.R
import dev.horizon.android.controls.ControlLayouts
import dev.horizon.android.controls.ControlSettings

/** The panel the ☰ button opens in game: pause, edit the layout, switch layouts, quick sliders. */
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
    val none = remember { MutableInteractionSource() }
    val panel = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = none, indication = null) { onClose() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(300.dp)
                .background(Color(0xF21C1C1C))
                .clickable(interactionSource = panel, indication = null) {}
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (page) {
                1 -> {
                    MenuItem(stringResource(R.string.menu_back)) { page = 0 }
                    CardTitle(stringResource(R.string.menu_layouts))
                    ControlLayouts.list(context, defaultName).forEach { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    settings.layout = e.id
                                    selected = e.id
                                    onLayout(e.id)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == e.id, onClick = {
                                settings.layout = e.id
                                selected = e.id
                                onLayout(e.id)
                            })
                            Text(e.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                2 -> {
                    MenuItem(stringResource(R.string.menu_back)) { page = 0 }
                    ControlsSettings(settings) { onSettingsChanged() }
                }
                else -> {
                    CardTitle(stringResource(R.string.menu_title))
                    MenuItem(stringResource(R.string.menu_close)) { onClose() }
                    MenuItem(stringResource(R.string.menu_pause)) { onPause() }
                    MenuItem(stringResource(R.string.menu_edit)) { onEdit() }
                    MenuItem(stringResource(R.string.menu_layouts)) { page = 1 }
                    MenuItem(stringResource(R.string.menu_quick)) { page = 2 }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start, fontWeight = FontWeight.SemiBold)
    }
}
