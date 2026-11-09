package dev.horizon.android.controls.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.horizon.android.R
import dev.horizon.android.controls.ControlLayouts
import dev.horizon.android.controls.ControlSettings
import java.io.File

enum class Change { LAYOUT, CONTROLS, MOUSE, RESOLUTION }

@Composable
fun LayoutSettings(settings: ControlSettings, onEdit: (() -> Unit)?, onChange: (Change) -> Unit) {
    val context = LocalContext.current
    val defaultName = stringResource(R.string.controls_default_name)
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(settings.layout) }
    val entries = remember(version) { ControlLayouts.list(context, defaultName) }
    val imported = stringResource(R.string.controls_imported, "%s")
    val failed = stringResource(R.string.controls_import_failed, "%s")
    fun choose(id: String) {
        settings.layout = id
        selected = id
        onChange(Change.LAYOUT)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ControlLayouts.import(context, uri) }
            .onSuccess {
                version++
                choose(it.id)
                Toast.makeText(context, imported.replace("%s", it.name), Toast.LENGTH_SHORT).show()
            }
            .onFailure { Toast.makeText(context, failed.replace("%s", it.message.orEmpty()), Toast.LENGTH_LONG).show() }
    }
    ControlsCard {
        CardTitle(stringResource(R.string.controls_layout))
        Hint(stringResource(R.string.controls_overlay_hint))
        entries.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choose(e.id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == e.id, onClick = { choose(e.id) })
                Text(e.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.removable) {
                    TextButton(onClick = {
                        ControlLayouts.delete(e.id)
                        version++
                        if (selected == e.id) choose(entries.first().id)
                    }) { Text(stringResource(R.string.controls_delete)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onEdit?.let { Button(onClick = it) { Text(stringResource(R.string.controls_edit)) } }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }) {
                Text(stringResource(R.string.controls_import))
            }
        }
        Hint(stringResource(R.string.controls_folder, ControlLayouts.dir.absolutePath))
    }
}

@Composable
fun ControlsSettings(settings: ControlSettings, onChange: (Change) -> Unit) {
    ControlsCard {
        CardTitle(stringResource(R.string.nav_controls))
        SettingSlider(stringResource(R.string.controls_opacity), settings.opacity, 0.10f..1.00f, 0.05f, { "${Math.round(it * 100)}%" }) { settings.opacity = it; onChange(Change.CONTROLS) }
        SettingSlider(stringResource(R.string.controls_size), settings.buttonScale, 0.50f..2.00f, 0.05f, { "${fmt(it)}x" }) { settings.buttonScale = it; onChange(Change.CONTROLS) }
        SettingSlider(stringResource(R.string.controls_sensitivity), settings.lookSensitivity, 0.10f..3.00f, 0.05f, { "${fmt(it)}x" }) { settings.lookSensitivity = it; onChange(Change.CONTROLS) }
        SettingSlider(stringResource(R.string.controls_long_press), settings.longPressMs.toFloat(), 150f..800f, 50f, { "${it.toInt()} ms" }) { settings.longPressMs = it.toInt(); onChange(Change.CONTROLS) }
        SettingSwitch(stringResource(R.string.controls_tap_use), settings.tapToUse) { settings.tapToUse = it; onChange(Change.CONTROLS) }
        SettingSwitch(stringResource(R.string.controls_hotbar), settings.hotbarTouch) { settings.hotbarTouch = it; onChange(Change.CONTROLS) }
        SettingSwitch(stringResource(R.string.controls_vibrate), settings.vibrate) { settings.vibrate = it; onChange(Change.CONTROLS) }
        Hint(stringResource(R.string.controls_help))
    }
}

@Composable
fun MouseSettings(settings: ControlSettings, onChange: (Change) -> Unit) {
    val context = LocalContext.current
    var image by remember { mutableStateOf(settings.cursorImage) }
    val failed = stringResource(R.string.controls_import_failed, "%s")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ControlLayouts.importCursor(context, uri) }
            .onSuccess {
                settings.cursorImage = it.absolutePath
                image = it.absolutePath
                onChange(Change.MOUSE)
            }
            .onFailure { Toast.makeText(context, failed.replace("%s", it.message.orEmpty()), Toast.LENGTH_LONG).show() }
    }
    ControlsCard {
        CardTitle(stringResource(R.string.nav_mouse))
        SettingSwitch(stringResource(R.string.mouse_virtual), settings.virtualMouse, stringResource(R.string.mouse_virtual_desc)) { settings.virtualMouse = it; onChange(Change.MOUSE) }
        SettingSlider(stringResource(R.string.mouse_speed), settings.mouseSpeed, 0.25f..3.00f, 0.05f, { "${fmt(it)}x" }) { settings.mouseSpeed = it; onChange(Change.MOUSE) }
        SettingSlider(stringResource(R.string.mouse_size), settings.cursorSize, 0.25f..2.00f, 0.05f, { "${fmt(it)}x" }) { settings.cursorSize = it; onChange(Change.MOUSE) }
        SettingSwitch(stringResource(R.string.mouse_tap_click), settings.tapToClick) { settings.tapToClick = it; onChange(Change.MOUSE) }
    }
    ControlsCard {
        CardTitle(stringResource(R.string.mouse_cursor_image))
        Hint(
            if (image.isEmpty()) stringResource(R.string.mouse_cursor_none)
            else stringResource(R.string.mouse_cursor_current, File(image).name)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.mouse_cursor_choose)) }
            if (image.isNotEmpty()) {
                OutlinedButton(onClick = {
                    settings.cursorImage = ""
                    image = ""
                    onChange(Change.MOUSE)
                }) { Text(stringResource(R.string.mouse_cursor_default)) }
            }
        }
        SettingSwitch(stringResource(R.string.mouse_cursor_center), settings.cursorCenter) { settings.cursorCenter = it; onChange(Change.MOUSE) }
    }
}

@Composable
fun MemorySettings(settings: ControlSettings) {
    val context = LocalContext.current
    val totalMb = remember {
        val info = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        (info.totalMem / (1024L * 1024L)).toInt()
    }
    val maxMb = ((totalMb - 1024) / 256 * 256).coerceAtLeast(1024)
    var auto by remember { mutableStateOf(settings.javaHeapMb <= 0) }
    ControlsCard {
        CardTitle(stringResource(R.string.memory_title))
        Hint(stringResource(R.string.memory_desc))
        Hint(stringResource(R.string.memory_total, totalMb))
        SettingSwitch(stringResource(R.string.memory_auto), auto, stringResource(R.string.memory_auto_desc)) {
            auto = it
            settings.javaHeapMb = if (it) 0 else 2048.coerceAtMost(maxMb)
        }
        if (!auto) {
            SettingSlider(
                stringResource(R.string.memory_heap),
                settings.javaHeapMb.coerceIn(512, maxMb).toFloat(),
                512f..maxMb.toFloat(),
                256f,
                { context.getString(R.string.memory_value, it.toInt()) }
            ) { settings.javaHeapMb = (Math.round(it / 256f) * 256).coerceIn(512, maxMb) }
        }
    }
}

@Composable
fun ResolutionSettings(settings: ControlSettings, onChange: (Change) -> Unit) {
    val context = LocalContext.current
    val (screenW, screenH) = remember { screenSize(context) }
    var fixed by remember { mutableStateOf(settings.fixedResolution) }
    var percent by remember { mutableIntStateOf(Math.round(settings.renderScale * 100)) }
    var percentText by remember { mutableStateOf(percent.toString()) }
    var widthText by remember { mutableStateOf(settings.fixedWidth.toString()) }
    var heightText by remember { mutableStateOf(settings.fixedHeight.toString()) }
    var version by remember { mutableIntStateOf(0) }
    fun applyPercent(p: Int) {
        val v = p.coerceIn(10, 100)
        percent = v
        percentText = v.toString()
        settings.renderScale = v / 100f
        version++
        onChange(Change.RESOLUTION)
    }
    fun applyFixed() {
        val w = widthText.toIntOrNull()?.coerceIn(64, 7680) ?: settings.fixedWidth
        val h = heightText.toIntOrNull()?.coerceIn(64, 4320) ?: settings.fixedHeight
        settings.fixedWidth = w
        settings.fixedHeight = h
        widthText = w.toString()
        heightText = h.toString()
        version++
        onChange(Change.RESOLUTION)
    }
    ControlsCard {
        CardTitle(stringResource(R.string.graphics_resolution))
        Hint(stringResource(R.string.graphics_screen, screenW, screenH))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !fixed, onClick = {
                fixed = false
                settings.fixedResolution = false
                version++
                onChange(Change.RESOLUTION)
            }, label = { Text(stringResource(R.string.graphics_mode_percent)) })
            FilterChip(selected = fixed, onClick = {
                fixed = true
                settings.fixedResolution = true
                version++
                onChange(Change.RESOLUTION)
            }, label = { Text(stringResource(R.string.graphics_mode_fixed)) })
        }
        if (!fixed) {
            SettingSlider(stringResource(R.string.graphics_mode_percent), percent.toFloat(), 10f..100f, 1f, { "${it.toInt()}%" }) { applyPercent(it.toInt()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = percentText,
                    onValueChange = { percentText = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.width(150.dp),
                    singleLine = true,
                    label = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { percentText.toIntOrNull()?.let(::applyPercent) })
                )
                Button(onClick = { percentText.toIntOrNull()?.let(::applyPercent) }) { Text(stringResource(R.string.graphics_apply)) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.graphics_width)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
                Text("×")
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.graphics_height)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { applyFixed() })
                )
                Button(onClick = { applyFixed() }) { Text(stringResource(R.string.graphics_apply)) }
            }
            Hint(stringResource(R.string.graphics_stretch))
        }
        val (rw, rh) = remember(version) { settings.renderSize(screenW, screenH) }
        Text(stringResource(R.string.graphics_render, rw, rh), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Hint(stringResource(R.string.graphics_more))
    }
}
