package dev.horizon.android.controls.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.window.DialogProperties
import dev.horizon.android.R
import dev.horizon.android.controls.ControlLayouts
import dev.horizon.android.controls.ControlSettings

// ---------------------------------------------------------------------------------------------
// Pieces shared by the launcher's layout manager and the editor's dialogs (Horizon colours).
// ---------------------------------------------------------------------------------------------

/** One layout, as ZalithLauncher lists it: controller icon, name, description, file name and View Info. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayoutCard(
    info: ControlLayouts.Info,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) HzCapsule else HzRow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(info.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (info.description.isNotBlank()) {
                Text(info.description, color = HzMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.layouts_file_name, info.fileName), color = HzMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Text(stringResource(R.string.layouts_is_default), color = HzAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HzCapsule).clickable(onClick = onInfo).padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.layouts_view_info), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
        Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** The "More Info" rows of a layout. */
@Composable
fun LayoutInfoBody(info: ControlLayouts.Info) {
    val unknown = stringResource(R.string.layouts_unknown)
    InfoRow(stringResource(R.string.layouts_name), info.name)
    InfoRow(stringResource(R.string.layouts_file), info.fileName)
    InfoRow(stringResource(R.string.layouts_author), info.author.ifBlank { unknown })
    InfoRow(stringResource(R.string.layouts_version), info.version.ifBlank { unknown })
    InfoRow(stringResource(R.string.layouts_description), info.description.ifBlank { unknown })
}

@Composable
fun HzLabeledField(label: String, value: String, placeholder: String = "", onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = HzMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp, top = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(HzAccent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(HzRow).padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = HzMuted, fontSize = 13.sp)
                inner()
            },
        )
    }
}

/** Cancel / Confirm, as in Zalith's dialogs. */
@Composable
fun HzDialogButtons(confirmLabel: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(HzCapsule).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.layouts_cancel), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Box(
            modifier = Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(HzAccent).clickable(onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) { Text(confirmLabel, color = HzOnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

/** The Save and Exit form (file name, layout name, version, author, description). */
@Composable
fun LayoutSaveForm(
    initialFile: String,
    initial: ControlLayouts.Info?,
    layoutName: String,
    onCancel: () -> Unit,
    onConfirm: (file: String, name: String, version: String, author: String, description: String) -> Unit,
) {
    var file by remember { mutableStateOf(initialFile) }
    var name by remember { mutableStateOf(layoutName) }
    var version by remember { mutableStateOf(initial?.version.orEmpty()) }
    var author by remember { mutableStateOf(initial?.author.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    val optional = stringResource(R.string.layouts_optional)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HzLabeledField(stringResource(R.string.layouts_file), file, onValue = { file = it }, modifier = Modifier.weight(1f))
        HzLabeledField(stringResource(R.string.layouts_name), name, onValue = { name = it }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HzLabeledField(stringResource(R.string.layouts_version), version, optional, { version = it }, Modifier.weight(1f))
        HzLabeledField(stringResource(R.string.layouts_author), author, optional, { author = it }, Modifier.weight(1f))
    }
    HzLabeledField(stringResource(R.string.layouts_description), description, optional, { description = it })
    HzDialogButtons(stringResource(R.string.layouts_confirm), onCancel) { onConfirm(file, name, version, author, description) }
}

@Composable
private fun HzWindowDialog(title: String, onDismiss: () -> Unit, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val style = LocalControlsCardStyle.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxWidth(0.7f).widthIn(max = 440.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(10.dp)).background(style.card).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), content = content)
        }
    }
}

@Composable
private fun PanelTool(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).background(LocalControlsCardStyle.current.row).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelRow(
    info: ControlLayouts.Info,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onInfo: () -> Unit,
) {
    val style = LocalControlsCardStyle.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.row)
            .then(if (selected) Modifier.border(1.dp, style.accent.copy(alpha = 0.75f), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = if (selected) style.accent else Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(info.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (selected) stringResource(R.string.layouts_file_name, info.fileName) + "  \u2022  " + stringResource(R.string.layouts_is_default)
                else stringResource(R.string.layouts_file_name, info.fileName),
                color = if (selected) style.accent else style.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(style.capsule).clickable(onClick = onInfo).padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.layouts_view_info), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun PanelField(label: String, value: String, placeholder: String = "", onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    val style = LocalControlsCardStyle.current
    Column(modifier = modifier) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp, top = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(style.accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(style.row).padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = style.muted, fontSize = 13.sp)
                inner()
            },
        )
    }
}

@Composable
private fun PanelButtons(confirmLabel: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val style = LocalControlsCardStyle.current
    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(style.capsule).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.layouts_cancel), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Box(
            modifier = Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(style.accent).clickable(onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) { Text(confirmLabel, color = style.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun OpItem(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Manage Control Layouts: an inline card of the launcher's Controls tab (no full-screen dialog).
// ---------------------------------------------------------------------------------------------

@Composable
fun ManageLayoutsPanel(
    settings: ControlSettings,
    onEdit: (() -> Unit)?,
    onChange: (Change) -> Unit,
) {
    val context = LocalContext.current
    val defaultName = stringResource(R.string.controls_default_name)
    val copySuffix = stringResource(R.string.layouts_copy_suffix)
    val imported = stringResource(R.string.controls_imported, "%s")
    val failed = stringResource(R.string.controls_import_failed, "%s")
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(settings.layout) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var operate by remember { mutableStateOf<ControlLayouts.Info?>(null) }
    var showInfo by remember { mutableStateOf<ControlLayouts.Info?>(null) }
    var editInfo by remember { mutableStateOf<ControlLayouts.Info?>(null) }
    var renaming by remember { mutableStateOf<ControlLayouts.Info?>(null) }
    var deleting by remember { mutableStateOf<ControlLayouts.Info?>(null) }
    var moving by remember { mutableStateOf<ControlLayouts.Info?>(null) }

    val infos = remember(version) {
        ControlLayouts.list(context, defaultName).map { e -> ControlLayouts.info(context, e, defaultName) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { version++; selected = settings.layout }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val shown = infos.filter { query.isBlank() || it.name.contains(query, true) || it.fileName.contains(query, true) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun choose(id: String, name: String) {
        settings.layout = id
        selected = id
        onChange(Change.LAYOUT)
        toast(context.getString(R.string.layouts_now_default, name))
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ControlLayouts.import(context, uri) }
            .onSuccess { version++; choose(it.id, it.name); toast(imported.replace("%s", it.name)) }
            .onFailure { Toast.makeText(context, failed.replace("%s", it.message.orEmpty()), Toast.LENGTH_LONG).show() }
    }
    val movePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val info = moving
        moving = null
        if (uri == null || info == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = ControlLayouts.exportText(context, info.id, defaultName)
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } ?: error("Could not write")
            if (info.removable) {
                ControlLayouts.delete(info.id)
                if (selected == info.id) choose(ControlLayouts.ZALITH_ID, context.getString(R.string.controls_zalith_name))
            }
            version++
        }.onFailure { Toast.makeText(context, failed.replace("%s", it.message.orEmpty()), Toast.LENGTH_LONG).show() }
    }

    ControlsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layouts_manage), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelTool(Icons.Filled.Refresh, stringResource(R.string.layouts_refresh)) { version++ }
                PanelTool(Icons.Filled.Search, stringResource(R.string.layouts_search)) { searching = !searching; if (!searching) query = "" }
                PanelTool(Icons.Filled.NoteAdd, stringResource(R.string.controls_import)) {
                    importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                }
                PanelTool(Icons.Filled.Add, stringResource(R.string.layouts_new)) {
                    val id = ControlLayouts.create(defaultName, context.getString(R.string.layouts_new_name))
                    version++
                    choose(id, context.getString(R.string.layouts_new_name))
                    onEdit?.invoke()
                }
            }
        }
        if (searching) PanelField(stringResource(R.string.layouts_search), query, onValue = { query = it })
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.forEach { info ->
                PanelRow(
                    info = info,
                    selected = selected == info.id,
                    onClick = { operate = info },
                    onLongClick = { choose(info.id, info.name) },
                    onInfo = { showInfo = info },
                )
            }
        }
        Hint(stringResource(R.string.layouts_long_press_hint))
    }

    showInfo?.let { info ->
        HzWindowDialog(
            stringResource(R.string.layouts_more_info), { showInfo = null },
            trailing = if (info.removable) ({
                Icon(
                    Icons.Filled.Edit, contentDescription = stringResource(R.string.layouts_edit_info), tint = Color.White,
                    modifier = Modifier.size(26.dp).clickable { editInfo = info; showInfo = null },
                )
            }) else null,
        ) {
            LayoutInfoBody(info)
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(LocalControlsCardStyle.current.capsule).clickable { showInfo = null },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.layouts_close), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }

    editInfo?.let { info ->
        HzWindowDialog(stringResource(R.string.layouts_edit_info), { editInfo = null }) {
            var name by remember { mutableStateOf(info.name) }
            var ver by remember { mutableStateOf(info.version) }
            var author by remember { mutableStateOf(info.author) }
            var desc by remember { mutableStateOf(info.description) }
            val optional = stringResource(R.string.layouts_optional)
            PanelField(stringResource(R.string.layouts_name), name, onValue = { name = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelField(stringResource(R.string.layouts_version), ver, optional, { ver = it }, Modifier.weight(1f))
                PanelField(stringResource(R.string.layouts_author), author, optional, { author = it }, Modifier.weight(1f))
            }
            PanelField(stringResource(R.string.layouts_description), desc, optional, { desc = it })
            PanelButtons(stringResource(R.string.layouts_confirm), { editInfo = null }) {
                runCatching { ControlLayouts.updateInfo(context, info.id, defaultName, name, ver, author, desc) }
                if (selected == info.id) onChange(Change.LAYOUT)
                editInfo = null
                version++
            }
        }
    }

    renaming?.let { info ->
        HzWindowDialog(stringResource(R.string.layouts_rename), { renaming = null }) {
            var text by remember { mutableStateOf(info.fileName.removeSuffix(".json")) }
            PanelField(stringResource(R.string.layouts_file), text, onValue = { text = it })
            PanelButtons(stringResource(R.string.layouts_confirm), { renaming = null }) {
                val newId = ControlLayouts.renameFile(info.id, text)
                if (newId == null) toast(context.getString(R.string.layouts_rename_failed))
                else {
                    if (selected == info.id) { settings.layout = newId; selected = newId }
                    version++
                }
                renaming = null
            }
        }
    }

    deleting?.let { info ->
        HzWindowDialog(stringResource(R.string.layouts_delete), { deleting = null }) {
            Text(stringResource(R.string.layouts_delete_confirm, info.fileName), color = Color.White, fontSize = 13.sp)
            PanelButtons(stringResource(R.string.controls_delete), { deleting = null }) {
                ControlLayouts.delete(info.id)
                if (selected == info.id) choose(ControlLayouts.ZALITH_ID, context.getString(R.string.controls_zalith_name))
                deleting = null
                version++
            }
        }
    }

    operate?.let { info ->
        val file = info.removable
        HzWindowDialog(stringResource(R.string.layouts_operate), { operate = null }) {
            Text(stringResource(R.string.layouts_operate_desc), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                OpItem(Icons.Filled.ContentCopy, stringResource(R.string.layouts_copy), true, Modifier.weight(1f)) {
                    runCatching { ControlLayouts.copy(context, info.id, defaultName, copySuffix) }
                    operate = null; version++
                }
                OpItem(Icons.Filled.DriveFileMove, stringResource(R.string.layouts_move), file, Modifier.weight(1f)) {
                    moving = info; operate = null
                    movePicker.launch(info.fileName)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                OpItem(Icons.Filled.Edit, stringResource(R.string.layouts_rename), file, Modifier.weight(1f)) { renaming = info; operate = null }
                OpItem(Icons.Filled.Delete, stringResource(R.string.layouts_delete), file, Modifier.weight(1f)) { deleting = info; operate = null }
            }
            Row(Modifier.fillMaxWidth()) {
                OpItem(Icons.Filled.Share, stringResource(R.string.layouts_share), true, Modifier.weight(1f)) {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, info.name)
                            .putExtra(Intent.EXTRA_TEXT, ControlLayouts.exportText(context, info.id, defaultName))
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    operate = null
                }
                OpItem(Icons.Filled.MoreHoriz, stringResource(R.string.layouts_edit), onEdit != null, Modifier.weight(1f)) {
                    choose(info.id, info.name)
                    operate = null
                    onEdit?.invoke()
                }
            }
        }
    }
}
