package dev.horizon.android.controls.ui

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import dev.horizon.android.R
import dev.horizon.android.controls.Action
import dev.horizon.android.controls.ActionCatalog
import dev.horizon.android.controls.ControlButton
import dev.horizon.android.controls.ControlLayer
import dev.horizon.android.controls.ControlLayout
import dev.horizon.android.controls.ControlLayouts
import dev.horizon.android.controls.ControlSettings
import dev.horizon.android.controls.ControlStick
import dev.horizon.android.controls.Size
import dev.horizon.android.controls.Visibility
import dev.horizon.android.controls.ControlsView
import dev.horizon.android.controls.Selection
import java.util.UUID

private enum class Mode { HOLD, TAP, TOGGLE }

private fun isHoldable(a: Action) = a is Action.Key || a is Action.Mouse

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlEditor(controls: ControlsView, settings: ControlSettings, onExit: () -> Unit) {
    val context = LocalContext.current
    var selection by remember { mutableStateOf(controls.selection) }
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var onLeft by remember { mutableStateOf(false) }
    val savedText = stringResource(R.string.editor_saved, "%s")
    val suffix = stringResource(R.string.editor_suffix)
    val defaultName = stringResource(R.string.controls_default_name)

    DisposableEffect(controls) {
        controls.onSelectionChanged = { s ->
            selection = s
            version++
            if (s != null) {
                val w = controls.width.toFloat().coerceAtLeast(1f)
                val cx = when (s) {
                    is Selection.Button -> controls.layout.buttons.getOrNull(s.index)?.x ?: 0.5f
                    is Selection.Stick -> controls.layout.sticks.getOrNull(s.index)?.x ?: 0.5f
                }
                onLeft = cx > 0.5f || w <= 0f
            }
        }
        controls.onLayoutEdited = {
            dirty = true
            version++
        }
        onDispose {
            controls.onSelectionChanged = null
            controls.onLayoutEdited = null
        }
    }

    fun layerId(layout: ControlLayout): Pair<ControlLayout, String> {
        val existing = layout.layers.firstOrNull { it.visible }?.id ?: layout.layers.firstOrNull()?.id
        if (existing != null) return layout to existing
        val id = "pojarush"
        return layout.copy(layers = layout.layers + ControlLayer(id, true, Visibility.ALWAYS)) to id
    }

    fun density() = context.resources.displayMetrics.density

    fun dpOf(size: Size): Float =
        size.dp ?: (size.px(controls.width, controls.height, density()) / density())

    fun updateButton(index: Int, change: (ControlButton) -> ControlButton) {
        val l = controls.layout
        val b = l.buttons.getOrNull(index) ?: return
        controls.editLayout(l.copy(buttons = l.buttons.toMutableList().also { it[index] = change(b) }), Selection.Button(index))
    }

    fun updateStick(index: Int, change: (ControlStick) -> ControlStick) {
        val l = controls.layout
        val s = l.sticks.getOrNull(index) ?: return
        controls.editLayout(l.copy(sticks = l.sticks.toMutableList().also { it[index] = change(s) }), Selection.Stick(index))
    }

    fun save() {
        val id = ControlLayouts.save(controls.layout, settings.layout, suffix)
        settings.layout = id
        dirty = false
        val name = ControlLayouts.list(context, defaultName).firstOrNull { it.id == id }?.name ?: id
        Toast.makeText(context, savedText.replace("%s", name), Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE6181B1E))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = {
                val (l, layer) = layerId(controls.layout)
                val b = ControlButton(
                    id = UUID.randomUUID().toString(), text = "A", layer = layer, x = 0.5f, y = 0.5f,
                    width = Size(56f), height = Size(56f), visibility = Visibility.IN_GAME,
                    press = listOf(Action.Key(KeyEvent.KEYCODE_A)),
                )
                controls.editLayout(l.copy(buttons = l.buttons + b), Selection.Button(l.buttons.size))
            }) { Text(stringResource(R.string.editor_add_button)) }
            TextButton(onClick = {
                val (l, layer) = layerId(controls.layout)
                val s = ControlStick(
                    id = UUID.randomUUID().toString(), layer = layer, x = 0.1f, y = 0.85f, size = Size(140f),
                    visibility = Visibility.IN_GAME,
                    directions = ControlLayout.wasd(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D),
                    lock = listOf(Action.Key(KeyEvent.KEYCODE_CTRL_LEFT)), canLock = true, lockThreshold = 0.25f, deadZone = 0.15f,
                )
                controls.editLayout(l.copy(sticks = l.sticks + s), Selection.Stick(l.sticks.size))
            }) { Text(stringResource(R.string.editor_add_stick)) }
            TextButton(onClick = { save() }) { Text(stringResource(R.string.editor_save), fontWeight = FontWeight.Bold) }
            TextButton(onClick = onExit) { Text(stringResource(if (dirty) R.string.editor_discard else R.string.editor_exit)) }
        }
        val current = selection
        if (current != null) {
            Column(
                modifier = Modifier
                    .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(8.dp)
                    .width(300.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF0181B1E))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onLeft = !onLeft }) { Text("⇆") }
                    Box(modifier = Modifier.weight(1f))
                    TextButton(onClick = { controls.select(null) }) { Text("✕") }
                }
                when (current) {
                    is Selection.Button -> controls.layout.buttons.getOrNull(current.index)?.let { b ->
                        ButtonEditor(b, version, ::dpOf, onChange = { updateButton(current.index, it) }, onDelete = {
                            val l = controls.layout
                            controls.editLayout(l.copy(buttons = l.buttons.filterIndexed { i, _ -> i != current.index }), null)
                        }, onDuplicate = {
                            val l = controls.layout
                            val copy = b.copy(id = UUID.randomUUID().toString(), x = (b.x + 0.05f).coerceAtMost(1f), y = (b.y + 0.05f).coerceAtMost(1f))
                            controls.editLayout(l.copy(buttons = l.buttons + copy), Selection.Button(l.buttons.size))
                        })
                    }
                    is Selection.Stick -> controls.layout.sticks.getOrNull(current.index)?.let { st ->
                        StickEditor(st, version, ::dpOf, onChange = { updateStick(current.index, it) }, onDelete = {
                            val l = controls.layout
                            controls.editLayout(l.copy(sticks = l.sticks.filterIndexed { i, _ -> i != current.index }), null)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun VisibilityChips(value: Visibility, onChange: (Visibility) -> Unit) {
    Text(stringResource(R.string.editor_visibility), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Visibility.ALWAYS to R.string.vis_always, Visibility.IN_GAME to R.string.vis_game, Visibility.IN_MENU to R.string.vis_menu).forEach { (v, label) ->
            FilterChip(selected = value == v, onClick = { onChange(v) }, label = { Text(stringResource(label)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonEditor(
    b: ControlButton,
    version: Int,
    dpOf: (Size) -> Float,
    onChange: ((ControlButton) -> ControlButton) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(b.id) { mutableStateOf(b.text) }
    val all = b.press + b.tap + b.toggle
    val mode = when {
        b.toggle.isNotEmpty() -> Mode.TOGGLE
        b.tap.isNotEmpty() && b.press.isEmpty() -> Mode.TAP
        else -> Mode.HOLD
    }
    fun withActions(actions: List<Action>, m: Mode): (ControlButton) -> ControlButton = { cb ->
        when (m) {
            Mode.HOLD -> cb.copy(press = actions, tap = emptyList(), toggle = emptyList())
            Mode.TAP -> cb.copy(press = emptyList(), tap = actions, toggle = emptyList())
            Mode.TOGGLE -> cb.copy(press = actions.filterNot(::isHoldable), tap = emptyList(), toggle = actions.filter(::isHoldable))
        }
    }
    var menuOpen by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.take(24); onChange { it.copy(text = text) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.editor_text)) }
    )
    SettingSlider(stringResource(R.string.editor_width), dpOf(b.width), 20f..240f, 2f, { "${it.toInt()} dp" }) { v -> onChange { it.copy(width = Size(v)) } }
    SettingSlider(stringResource(R.string.editor_height), dpOf(b.height), 20f..240f, 2f, { "${it.toInt()} dp" }) { v -> onChange { it.copy(height = Size(v)) } }
    Text(stringResource(R.string.editor_mode), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Mode.HOLD to R.string.editor_mode_hold, Mode.TAP to R.string.editor_mode_tap, Mode.TOGGLE to R.string.editor_mode_toggle).forEach { (m, label) ->
            FilterChip(selected = mode == m, onClick = { onChange(withActions(all, m)) }, label = { Text(stringResource(label)) })
        }
    }
    Text(stringResource(R.string.editor_actions), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        all.forEachIndexed { i, a ->
            AssistChip(onClick = { onChange(withActions(all.filterIndexed { j, _ -> j != i }, mode)) }, label = { Text("${ActionCatalog.label(context, a)}  ×") })
        }
        Box {
            AssistChip(onClick = { menuOpen = true }, label = { Text(stringResource(R.string.editor_add_action)) })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                ActionCatalog.all(context).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menuOpen = false
                        if (action !in all) onChange(withActions(all + action, mode))
                    })
                }
            }
        }
    }
    VisibilityChips(b.visibility) { v -> onChange { it.copy(visibility = v) } }
    SettingSwitch(stringResource(R.string.editor_swipe), b.swipe) { v -> onChange { it.copy(swipe = v) } }
    SettingSwitch(stringResource(R.string.editor_penetrable), b.penetrable) { v -> onChange { it.copy(penetrable = v) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDuplicate) { Text(stringResource(R.string.editor_duplicate)) }
        OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StickEditor(
    s: ControlStick,
    version: Int,
    dpOf: (Size) -> Float,
    onChange: ((ControlStick) -> ControlStick) -> Unit,
    onDelete: () -> Unit,
) {
    SettingSlider(stringResource(R.string.editor_size), dpOf(s.size), 60f..360f, 4f, { "${it.toInt()} dp" }) { v -> onChange { it.copy(size = Size(v)) } }
    VisibilityChips(s.visibility) { v -> onChange { it.copy(visibility = v) } }
    SettingSwitch(stringResource(R.string.editor_sprint_lock), s.canLock) { v -> onChange { it.copy(canLock = v, lock = if (v && it.lock.isEmpty()) listOf(Action.Key(KeyEvent.KEYCODE_CTRL_LEFT)) else it.lock) } }
    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error) }
}
