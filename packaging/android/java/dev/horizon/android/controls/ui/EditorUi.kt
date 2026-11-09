package dev.horizon.android.controls.ui

import android.content.Intent
import android.graphics.RectF
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.R
import dev.horizon.android.controls.Action
import dev.horizon.android.controls.ActionCatalog
import dev.horizon.android.controls.ControlButton
import dev.horizon.android.controls.ControlLayer
import dev.horizon.android.controls.ControlLayout
import dev.horizon.android.controls.ControlLayouts
import dev.horizon.android.controls.ControlSettings
import dev.horizon.android.controls.ControlStick
import dev.horizon.android.controls.ControlsView
import dev.horizon.android.controls.Selection
import dev.horizon.android.controls.Size
import dev.horizon.android.controls.Style
import dev.horizon.android.controls.Visibility
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// Colours and wording of ZalithLauncher's "Edit Control Layout" screens.
private val ZPanel = Color(0xFF1C1C1C)
private val ZItem = Color(0xFF2A2A2A)
private val ZBox = Color(0xFF4A4A4A)
private val ZTeal = Color(0xFF80CBC4)
private val ZMuted = Color(0xFFB4B4B4)
private val ZLine = Color(0xFF6E6E6E)
private val ZScrim = Color(0x99000000)

private enum class Mode { HOLD, TAP, TOGGLE }

private fun isHoldable(a: Action) = a is Action.Key || a is Action.Mouse

private sealed interface Dialog {
    data object Load : Dialog
    data object SelectDefault : Dialog
    data object ConfirmExit : Dialog
    data class Slot(val index: Int) : Dialog
    data class Pick(val background: Boolean) : Dialog
}

@Composable
fun ControlEditor(controls: ControlsView, settings: ControlSettings, onExit: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var selection by remember { mutableStateOf(controls.selection) }
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var onLeft by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var currentId by remember { mutableStateOf(settings.layout) }
    var snapOn by remember { mutableStateOf(settings.snapEnabled) }
    var snapDp by remember { mutableFloatStateOf(settings.snapDp) }
    val savedText = stringResource(R.string.editor_saved, "%s")
    val suffix = stringResource(R.string.editor_suffix)
    val defaultName = stringResource(R.string.controls_default_name)

    DisposableEffect(controls) {
        controls.onSelectionChanged = { s ->
            selection = s
            version++
            if (s != null) {
                menuOpen = false
                val cx = when (s) {
                    is Selection.Button -> controls.layout.buttons.getOrNull(s.index)?.x ?: 0.5f
                    is Selection.Stick -> controls.layout.sticks.getOrNull(s.index)?.x ?: 0.5f
                }
                onLeft = cx > 0.5f
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

    fun mainLayer(layout: ControlLayout): Pair<ControlLayout, String> {
        val existing = layout.layers.firstOrNull { it.visible }?.id ?: layout.layers.firstOrNull()?.id
        if (existing != null) return layout to existing
        val id = "pojarush"
        return layout.copy(layers = layout.layers + ControlLayer(id, true, Visibility.ALWAYS)) to id
    }

    fun dpf() = context.resources.displayMetrics.density

    fun dpOf(size: Size): Float = size.dp ?: (size.px(controls.width, controls.height, dpf()) / dpf())

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

    fun save(): Boolean = runCatching {
        val id = ControlLayouts.save(controls.layout, currentId, suffix)
        settings.layout = id
        currentId = id
        dirty = false
        val name = ControlLayouts.list(context, defaultName).firstOrNull { it.id == id }?.name ?: id
        Toast.makeText(context, savedText.replace("%s", name), Toast.LENGTH_SHORT).show()
    }.isSuccess

    fun addButton() {
        val (l, layer) = mainLayer(controls.layout)
        val b = ControlButton(
            id = UUID.randomUUID().toString(), text = "New", layer = layer, x = 0.5f, y = 0.5f,
            width = Size(50f), height = Size(50f), visibility = Visibility.IN_GAME, press = emptyList(),
        )
        controls.editLayout(l.copy(buttons = l.buttons + b), Selection.Button(l.buttons.size))
    }

    fun addJoystick() {
        val (l, layer) = mainLayer(controls.layout)
        val s = ControlStick(
            id = UUID.randomUUID().toString(), layer = layer, x = 0.1f, y = 0.85f, size = Size(140f),
            visibility = Visibility.IN_GAME,
            directions = ControlLayout.wasd(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D),
            lock = listOf(Action.Key(KeyEvent.KEYCODE_CTRL_LEFT)), canLock = true, lockThreshold = 0.25f, deadZone = 0.15f,
        )
        controls.editLayout(l.copy(sticks = l.sticks + s), Selection.Stick(l.sticks.size))
    }

    /** A drawer is a button that shows and hides a layer of its own; its buttons live in that layer. */
    fun addDrawer() {
        val (l, layer) = mainLayer(controls.layout)
        val drawer = "drawer-" + UUID.randomUUID().toString().take(8)
        val toggle = ControlButton(
            id = UUID.randomUUID().toString(), text = "Drawer", layer = layer, x = 0.5f, y = 0.5f,
            width = Size(50f), height = Size(50f), visibility = Visibility.IN_GAME,
            press = listOf(Action.Layer(listOf(drawer), Action.LAYER_TOGGLE)),
        )
        val child = ControlButton(
            id = UUID.randomUUID().toString(), text = "New", layer = drawer, x = 0.58f, y = 0.5f,
            width = Size(50f), height = Size(50f), visibility = Visibility.IN_GAME, press = emptyList(),
        )
        controls.editLayout(
            l.copy(layers = l.layers + ControlLayer(drawer, false, Visibility.ALWAYS), buttons = l.buttons + toggle + child),
            Selection.Button(l.buttons.size),
        )
    }

    fun drawerOf(b: ControlButton): String? {
        val layers = controls.layout.layers
        return b.press.filterIsInstance<Action.Layer>().firstOrNull { a ->
            a.ids.size == 1 && layers.any { it.id == a.ids[0] && !it.visible }
        }?.ids?.get(0)
    }

    fun share() {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, controls.layout.name)
                .putExtra(Intent.EXTRA_TEXT, ControlLayout.write(controls.layout))
            context.startActivity(Intent.createChooser(send, null))
        }
    }

    val rect: RectF? = remember(version, selection) { controls.selectionRect() }

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            "Tap the gear to open the menu, drag it to move it\nTap a button to customize: edit, resize, delete, or remap",
            color = ZMuted, fontSize = 13.sp, textAlign = TextAlign.End,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 6.dp, vertical = 2.dp)
        )
        val gearPx = with(density) { 44.dp.toPx() }
        var gearX by remember { mutableFloatStateOf(settings.editorGearX) }
        var gearY by remember { mutableFloatStateOf(settings.editorGearY) }
        var gearDrag by remember { mutableStateOf(false) }
        val gearScale by animateFloatAsState(if (gearDrag) 1.12f else 1f, label = "gear")
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        ((controls.width - gearPx).coerceAtLeast(0f) * gearX).roundToInt(),
                        ((controls.height - gearPx).coerceAtLeast(0f) * gearY).roundToInt()
                    )
                }
                .size(44.dp)
                .graphicsLayer { scaleX = gearScale; scaleY = gearScale }
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2E373D), Color(0xFF1A2024))))
                .border(1.dp, Color(0xFF394952), RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectTapGestures {
                        menuOpen = !menuOpen
                        if (menuOpen) controls.select(null)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { gearDrag = true },
                        onDragEnd = {
                            gearDrag = false
                            settings.editorGearX = gearX
                            settings.editorGearY = gearY
                        },
                        onDragCancel = { gearDrag = false },
                    ) { change, drag ->
                        change.consume()
                        val rx = (controls.width - gearPx).coerceAtLeast(1f)
                        val ry = (controls.height - gearPx).coerceAtLeast(1f)
                        gearX = (gearX + drag.x / rx).coerceIn(0f, 1f)
                        gearY = (gearY + drag.y / ry).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center
        ) { GlyphIcon(Glyph.GEAR, Color(0xFF8BE0FF), Modifier.size(26.dp)) }

        // Delete / Clone sit right above the selected button, like Zalith's handle.
        val sel = selection
        if (sel != null && rect != null) {
            val barW = with(density) { (if (sel is Selection.Button) 3 * 102 else 2 * 102).dp.toPx() }
            val barH = with(density) { 44.dp.toPx() }
            val gap = with(density) { 8.dp.toPx() }
            val x = (rect.centerX() - barW / 2f).coerceIn(0f, (controls.width - barW).coerceAtLeast(0f))
            val above = rect.top - barH - gap
            val y = if (above >= 0f) above else rect.bottom + gap
            val drawer = (sel as? Selection.Button)?.let { s -> controls.layout.buttons.getOrNull(s.index)?.let(::drawerOf) }
            Row(
                modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ZPill("Delete") {
                    val l = controls.layout
                    when (sel) {
                        is Selection.Button -> controls.editLayout(l.copy(buttons = l.buttons.filterIndexed { i, _ -> i != sel.index }), null)
                        is Selection.Stick -> controls.editLayout(l.copy(sticks = l.sticks.filterIndexed { i, _ -> i != sel.index }), null)
                    }
                }
                ZPill("Clone") {
                    val l = controls.layout
                    when (sel) {
                        is Selection.Button -> l.buttons.getOrNull(sel.index)?.let { b ->
                            val copy = b.copy(id = UUID.randomUUID().toString(), x = (b.x + 0.04f).coerceAtMost(1f), y = (b.y + 0.04f).coerceAtMost(1f))
                            controls.editLayout(l.copy(buttons = l.buttons + copy), Selection.Button(l.buttons.size))
                        }
                        is Selection.Stick -> l.sticks.getOrNull(sel.index)?.let { s ->
                            val copy = s.copy(id = UUID.randomUUID().toString(), x = (s.x + 0.04f).coerceAtMost(1f), y = (s.y + 0.04f).coerceAtMost(1f))
                            controls.editLayout(l.copy(sticks = l.sticks + copy), Selection.Stick(l.sticks.size))
                        }
                    }
                }
                if (sel is Selection.Button && drawer != null) {
                    ZPill("+ Sub") {
                        val l = controls.layout
                        val owner = l.buttons.getOrNull(sel.index) ?: return@ZPill
                        val child = ControlButton(
                            id = UUID.randomUUID().toString(), text = "New", layer = drawer,
                            x = (owner.x + 0.08f * (1 + l.buttons.count { it.layer == drawer })).coerceAtMost(1f), y = owner.y,
                            width = Size(50f), height = Size(50f), visibility = Visibility.IN_GAME, press = emptyList(),
                        )
                        controls.editLayout(l.copy(buttons = l.buttons + child), Selection.Button(l.buttons.size))
                    }
                }
            }
        }

        if (sel != null) {
            Column(
                modifier = Modifier
                    .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .zPanel()
            ) {
                when (sel) {
                    is Selection.Button -> controls.layout.buttons.getOrNull(sel.index)?.let { b ->
                        ButtonPanel(
                            b = b, controls = controls, dpOf = ::dpOf,
                            onChange = { updateButton(sel.index, it) },
                            onSlot = { dialog = Dialog.Slot(it) },
                            onColor = { dialog = Dialog.Pick(it) },
                        )
                    }
                    is Selection.Stick -> controls.layout.sticks.getOrNull(sel.index)?.let { st ->
                        StickPanel(
                            s = st, controls = controls, dpOf = ::dpOf,
                            onChange = { updateStick(sel.index, it) },
                            onColor = { dialog = Dialog.Pick(it) },
                        )
                    }
                }
            }
        }

        if (menuOpen) {
            Column(modifier = Modifier.align(Alignment.CenterEnd).zPanel()) {
                Text("Edit Control Layout", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterHorizontally))
                ZSection("Edit Control Layout")
                ZButton("Add Button") { addButton(); menuOpen = false }
                ZButton("Add Drawer") { addDrawer(); menuOpen = false }
                ZButton("Add Joystick") { addJoystick(); menuOpen = false }
                ZSection("Save")
                ZButton("Load") { dialog = Dialog.Load }
                ZButton("Save") { save() }
                ZButton("Save and Exit") { if (save()) onExit() }
                ZButton("Save and Share") { if (save()) share() }
                ZSection("Control Layout")
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ZItem).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Button Snapping", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    ZSwitch(snapOn) { snapOn = it; settings.snapEnabled = it }
                }
                Text("Button Snapping Distance", color = ZMuted, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("-", color = ZMuted, fontSize = 22.sp, modifier = Modifier.clickable { snapDp = (snapDp - 1f).coerceAtLeast(1f); settings.snapDp = snapDp }.padding(6.dp))
                    Slider(
                        value = snapDp, onValueChange = { snapDp = it.roundToInt().toFloat() },
                        onValueChangeFinished = { settings.snapDp = snapDp },
                        valueRange = 1f..32f, modifier = Modifier.weight(1f), colors = zSliderColors()
                    )
                    Text("+", color = ZMuted, fontSize = 22.sp, modifier = Modifier.clickable { snapDp = (snapDp + 1f).coerceAtMost(32f); settings.snapDp = snapDp }.padding(6.dp))
                    Text(
                        "${snapDp.roundToInt()} dp", color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(ZItem).padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                ZButton("Select Default Control Layout") { dialog = Dialog.SelectDefault }
                ZButton("Exit Editor") { if (dirty) dialog = Dialog.ConfirmExit else onExit() }
            }
        }

        when (val d = dialog) {
            Dialog.Load -> ZDialog("Load", { dialog = null }) {
                ControlLayouts.list(context, defaultName).forEach { e ->
                    ZPlain(e.name) {
                        controls.editLayout(ControlLayouts.load(context, e.id, defaultName), null)
                        currentId = e.id
                        dirty = false
                        dialog = null
                        menuOpen = false
                    }
                }
            }
            Dialog.SelectDefault -> ZDialog("Select Default Control Layout", { dialog = null }) {
                ControlLayouts.list(context, defaultName).forEach { e ->
                    ZPlain(if (e.id == settings.layout) "${e.name} (default)" else e.name) {
                        settings.layout = e.id
                        Toast.makeText(context, e.name, Toast.LENGTH_SHORT).show()
                        dialog = null
                    }
                }
            }
            Dialog.ConfirmExit -> ZDialog("Exit without saving?", { dialog = null }) {
                ZPlain("Exit Editor") { dialog = null; onExit() }
                ZPlain("Save and Exit") { dialog = null; if (save()) onExit() }
                ZPlain("Cancel") { dialog = null }
            }
            is Dialog.Slot -> {
                val s = selection
                val b = (s as? Selection.Button)?.let { controls.layout.buttons.getOrNull(it.index) }
                if (s is Selection.Button && b != null) {
                    ZDialog("Mapping", { dialog = null }) {
                        ZPlain("Unspecified") { dialog = null; updateButton(s.index) { setSlot(it, d.index, null) } }
                        ActionCatalog.all(context).forEach { (label, action) ->
                            ZPlain(label) { dialog = null; updateButton(s.index) { setSlot(it, d.index, action) } }
                        }
                    }
                } else {
                    dialog = null
                }
            }
            is Dialog.Pick -> {
                val s = selection
                val current: Int? = when (s) {
                    is Selection.Button -> controls.layout.buttons.getOrNull(s.index)?.let { b ->
                        controls.effectiveStyle(b).let { if (d.background) it.fill else it.stroke }
                    }
                    is Selection.Stick -> controls.layout.sticks.getOrNull(s.index)?.let { st ->
                        controls.effectiveStyle(st).let { if (d.background) it.fill else it.stroke }
                    }
                    null -> null
                }
                if (s != null && current != null) {
                    ColorDialog(current, { dialog = null }) { color ->
                        dialog = null
                        when (s) {
                            is Selection.Button -> updateButton(s.index) { b ->
                                b.copy(style = controls.effectiveStyle(b).let { st -> if (d.background) st.copy(fill = color) else st.copy(stroke = color) })
                            }
                            is Selection.Stick -> updateStick(s.index) { st0 ->
                                st0.copy(style = controls.effectiveStyle(st0).let { st -> if (d.background) st.copy(fill = color) else st.copy(stroke = color) })
                            }
                        }
                    }
                } else {
                    dialog = null
                }
            }
            null -> {}
        }
    }
}

private fun slotsOf(b: ControlButton): List<Action> = when {
    b.toggle.isNotEmpty() -> b.press + b.toggle
    b.tap.isNotEmpty() && b.press.isEmpty() -> b.tap
    else -> b.press
}

private fun modeOf(b: ControlButton) = when {
    b.toggle.isNotEmpty() -> Mode.TOGGLE
    b.tap.isNotEmpty() && b.press.isEmpty() -> Mode.TAP
    else -> Mode.HOLD
}

private fun withActions(b: ControlButton, actions: List<Action>, mode: Mode): ControlButton = when (mode) {
    Mode.HOLD -> b.copy(press = actions, tap = emptyList(), toggle = emptyList())
    Mode.TAP -> b.copy(press = emptyList(), tap = actions, toggle = emptyList())
    Mode.TOGGLE -> b.copy(press = actions.filterNot(::isHoldable), tap = emptyList(), toggle = actions.filter(::isHoldable))
}

private fun setSlot(b: ControlButton, index: Int, action: Action?): ControlButton {
    val slots = MutableList<Action?>(4) { slotsOf(b).getOrNull(it) }
    slots[index] = action
    val list = slots.filterNotNull().distinct()
    return withActions(b, list, modeOf(b))
}

@Composable
private fun Modifier.zPanel(): Modifier = this
    .padding(12.dp)
    .width(280.dp)
    .fillMaxHeight()
    .clip(RoundedCornerShape(8.dp))
    .background(ZPanel)
    .pointerInput(Unit) { detectTapGestures { } }
    .verticalScroll(rememberScrollState())
    .padding(10.dp)

// ---------------------------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ButtonPanel(
    b: ControlButton,
    controls: ControlsView,
    dpOf: (Size) -> Float,
    onChange: ((ControlButton) -> ControlButton) -> Unit,
    onSlot: (Int) -> Unit,
    onColor: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val slots = slotsOf(b)
    fun label(i: Int): String = slots.getOrNull(i)?.let { ActionCatalog.label(context, it) } ?: "Unspecified"
    val wDp = dpOf(b.width)
    val hDp = dpOf(b.height)
    val st = controls.effectiveStyle(b)

    ZLabel("Name")
    ZField(key = b.id, initial = b.text, numeric = false) { v -> onChange { it.copy(text = v.take(24)) } }
    ZLabel("Size")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) { ZField(key = b.id + "w", initial = fmt1(wDp), numeric = true) { v -> v.toFloatOrNull()?.takeIf { it in 8f..700f }?.let { n -> onChange { it.copy(width = Size(n)) } } } }
        Text("x", color = Color.White, fontSize = 16.sp)
        Box(Modifier.weight(1f)) { ZField(key = b.id + "h", initial = fmt1(hDp), numeric = true) { v -> v.toFloatOrNull()?.takeIf { it in 8f..700f }?.let { n -> onChange { it.copy(height = Size(n)) } } } }
    }
    ZLabel("Mapping")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ZSpinner(label(0)) { onSlot(0) } }
        Text("+", color = ZMuted, fontSize = 18.sp)
        Box(Modifier.weight(1f)) { ZSpinner(label(1)) { onSlot(1) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ZSpinner(label(2)) { onSlot(2) } }
        Text("+", color = ZMuted, fontSize = 18.sp)
        Box(Modifier.weight(1f)) { ZSpinner(label(3)) { onSlot(3) } }
    }
    ZSwitchRow("Toggleable", modeOf(b) == Mode.TOGGLE) { on ->
        onChange { cb -> withActions(cb, slotsOf(cb), if (on) Mode.TOGGLE else Mode.HOLD) }
    }
    ZSwitchRow("Mouse Passthrough", b.penetrable) { v -> onChange { it.copy(penetrable = v) } }
    ZSwitchRow("Swipeable", b.swipe) { v -> onChange { it.copy(swipe = v) } }
    StyleSection(st, minOf(wDp, hDp) / 2f, b.visibility, controls, onColor,
        onStyle = { f -> onChange { cb -> cb.copy(style = f(controls.effectiveStyle(cb))) } },
        onVisibility = { v -> onChange { it.copy(visibility = v) } })
}

@Composable
private fun ColumnScope.StickPanel(
    s: ControlStick,
    controls: ControlsView,
    dpOf: (Size) -> Float,
    onChange: ((ControlStick) -> ControlStick) -> Unit,
    onColor: (Boolean) -> Unit,
) {
    val size = dpOf(s.size)
    val st = controls.effectiveStyle(s)
    ZLabel("Size")
    ZField(key = s.id + "s", initial = fmt1(size), numeric = true) { v -> v.toFloatOrNull()?.takeIf { it in 40f..500f }?.let { n -> onChange { it.copy(size = Size(n)) } } }
    ZSwitchRow("Forward Lock", s.canLock) { v ->
        onChange { it.copy(canLock = v, lock = if (v && it.lock.isEmpty()) listOf(Action.Key(KeyEvent.KEYCODE_CTRL_LEFT)) else it.lock) }
    }
    StyleSection(st, size / 2f, s.visibility, controls, onColor,
        onStyle = { f -> onChange { c -> c.copy(style = f(controls.effectiveStyle(c))) } },
        onVisibility = { v -> onChange { it.copy(visibility = v) } })
}

@Composable
private fun ColumnScope.StyleSection(
    st: Style,
    halfDp: Float,
    visibility: Visibility,
    controls: ControlsView,
    onColor: (Boolean) -> Unit,
    onStyle: ((Style) -> Style) -> Unit,
    onVisibility: (Visibility) -> Unit,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val radiusDp = if (st.radiusInPx) st.radiusDp / density else st.radiusDp
    fun pct(v: Float) = if (halfDp > 0f) (v / halfDp * 100f).coerceIn(0f, 100f).roundToInt() else 0

    ZChevron("Background Color") { onColor(true) }
    ZChevron("Stroke Color") { onColor(false) }
    ZSeek("Stroke Width", pct(st.strokeDp)) { p -> onStyle { it.copy(strokeDp = p / 100f * halfDp) } }
    ZSeek("Corner Radius", pct(radiusDp)) { p -> onStyle { it.copy(radiusDp = p / 100f * halfDp, radiusInPx = false) } }
    ZSeek("Button Opacity", (st.opacity * 100f).roundToInt()) { p -> onStyle { it.copy(opacity = p / 100f) } }
    ZLabel("Visibility")
    val game = visibility != Visibility.IN_MENU
    val menu = visibility != Visibility.IN_GAME
    fun apply(g: Boolean, m: Boolean) {
        // At least one stays on: a button hidden everywhere could not be found again.
        val v = when {
            g && m -> Visibility.ALWAYS
            g -> Visibility.IN_GAME
            m -> Visibility.IN_MENU
            else -> return
        }
        onVisibility(v)
    }
    ZCheck("In-Game", game) { apply(it, menu) }
    ZCheck("In-Menu", menu) { apply(game, it) }
}

private fun fmt1(v: Float) = String.format(Locale.ROOT, "%.1f", v)

// ---------------------------------------------------------------------------------------------
// Widgets
// ---------------------------------------------------------------------------------------------

@Composable
private fun ZPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(100.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF343434)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ZSection(title: String) {
    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZLine))
}

@Composable
private fun ZButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(ZItem).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 17.sp) }
}

@Composable
private fun ZLabel(text: String) {
    Text(text, color = ZMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ZField(key: String, initial: String, numeric: Boolean, onValue: (String) -> Unit) {
    ZFieldBox(key, initial, numeric, onValue)
}

@Composable
private fun ZFieldBox(key: String, initial: String, numeric: Boolean, onValue: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(initial) }
    BasicTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(ZTeal),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ZBox).padding(horizontal = 10.dp, vertical = 14.dp),
    )
}

@Composable
private fun ZSpinner(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

@Composable
private fun ZSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = ZTeal, checkedTrackColor = Color(0xFF3F6460), checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color(0xFFC8C8C8), uncheckedTrackColor = Color(0xFF555555), uncheckedBorderColor = Color.Transparent,
        )
    )
}

@Composable
private fun ZSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        ZSwitch(value, onChange)
    }
}

@Composable
private fun ZChevron(label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, color = ZMuted, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text("›", color = Color.White, fontSize = 24.sp)
    }
}

@Composable
private fun zSliderColors() = SliderDefaults.colors(
    thumbColor = ZTeal, activeTrackColor = ZTeal, inactiveTrackColor = Color(0xFF555555),
)

@Composable
private fun ZSeek(label: String, value: Int, onDone: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = ZMuted, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text("${current.roundToInt()}%", color = ZMuted, fontSize = 17.sp)
    }
    Slider(
        value = current, onValueChange = { current = it },
        onValueChangeFinished = { onDone(current.roundToInt()) },
        valueRange = 0f..100f, colors = zSliderColors(), modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ZCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!value) }) {
        Checkbox(
            checked = value, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = ZTeal, checkmarkColor = Color.Black, uncheckedColor = Color(0xFFCCCCCC))
        )
        Text(label, color = Color.White, fontSize = 17.sp)
    }
}

@Composable
private fun ZPlain(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 13.dp)
    )
}

/** A Zalith-style dialog drawn inside the editor, so it needs no window of its own. */
@Composable
private fun ZDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(ZScrim).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 340.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ZPanel)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp)
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), content = content)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Colour picker: saturation/value square, hue bar, alpha bar and a hex field, like Zalith's.
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColorDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(initial) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initial) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(initial) { mutableFloatStateOf(android.graphics.Color.alpha(initial) / 255f) }
    var hexText by remember(initial) { mutableStateOf(hex(initial)) }
    var typing by remember { mutableStateOf(false) }

    fun current(): Int = android.graphics.Color.HSVToColor((alpha * 255f).roundToInt().coerceIn(0, 255), floatArrayOf(hue, sat, value))
    fun refresh() { if (!typing) hexText = hex(current()) }

    ZDialog("Color", onDismiss) {
        val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        Canvas(
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures { o -> sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); typing = false; refresh() } }
                .pointerInput(Unit) { detectDragGestures { c, _ -> sat = (c.position.x / size.width).coerceIn(0f, 1f); value = 1f - (c.position.y / size.height).coerceIn(0f, 1f); typing = false; refresh() } }
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 9f, center = p, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
        Canvas(
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures { o -> hue = (o.x / size.width).coerceIn(0f, 1f) * 360f; typing = false; refresh() } }
                .pointerInput(Unit) { detectDragGestures { c, _ -> hue = (c.position.x / size.width).coerceIn(0f, 1f) * 360f; typing = false; refresh() } }
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            val x = hue / 360f * size.width
            drawRect(Color.White, topLeft = Offset(x - 3f, 0f), size = GeoSize(6f, size.height))
        }
        Canvas(
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF777777))
                .pointerInput(Unit) { detectTapGestures { o -> alpha = (o.x / size.width).coerceIn(0f, 1f); typing = false; refresh() } }
                .pointerInput(Unit) { detectDragGestures { c, _ -> alpha = (c.position.x / size.width).coerceIn(0f, 1f); typing = false; refresh() } }
        ) {
            val base = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
            drawRect(Brush.horizontalGradient(listOf(base.copy(alpha = 0f), base)))
            val x = alpha * size.width
            drawRect(Color.White, topLeft = Offset(x - 3f, 0f), size = GeoSize(6f, size.height))
        }
        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF777777)).padding(3.dp).background(Color(current())))
            BasicTextField(
                value = hexText,
                onValueChange = { t ->
                    hexText = t
                    typing = true
                    parseHex(t)?.let { c ->
                        val h = FloatArray(3)
                        android.graphics.Color.colorToHSV(c, h)
                        hue = h[0]; sat = h[1]; value = h[2]
                        alpha = android.graphics.Color.alpha(c) / 255f
                    }
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(ZTeal),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(ZBox).padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            Text("Cancel", color = ZTeal, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
            Text("OK", color = ZTeal, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onPick(current()) }.padding(12.dp))
        }
    }
}

private fun hex(c: Int): String = String.format(Locale.ROOT, "#%08X", c)

/** #RRGGBB or #AARRGGBB, with or without the #. */
private fun parseHex(t: String): Int? {
    val s = t.trim().removePrefix("#")
    if (s.length != 6 && s.length != 8) return null
    val v = s.toLongOrNull(16) ?: return null
    return if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
}
