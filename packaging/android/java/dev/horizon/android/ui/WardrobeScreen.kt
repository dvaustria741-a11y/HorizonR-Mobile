package dev.horizon.android.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.bridge.SkinTools
import dev.horizon.android.model.AccountType
import dev.horizon.android.model.HorizonConfigState
import dev.horizon.android.model.LauncherAccount
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.horizon.android.bridge.HorizonNativeBridge

private val LocalWAccent = androidx.compose.runtime.compositionLocalOf { Color(0xFFFFB454) }
private val LocalWOnAccent = androidx.compose.runtime.compositionLocalOf { Color(0xFF2A1700) }
private val LocalWCard = androidx.compose.runtime.compositionLocalOf { Color(0xFF161B2E) }
private val LocalWRow = androidx.compose.runtime.compositionLocalOf { Color(0xFF1E2438) }

// Colours come from the launcher theme, so the wardrobe follows whichever palette is selected.
private val WGreen: Color @Composable get() = LocalWAccent.current
private val WOnGreen: Color @Composable get() = LocalWOnAccent.current
private val WCardBg: Color @Composable get() = LocalWCard.current
private val WRowBg: Color @Composable get() = LocalWRow.current
private val WDanger = Color(0xFFFF8A80)

private class FetchOutcome(val account: LauncherAccount, val message: String, val ok: Boolean)

/**
 * Volt-style "Account & Custom Skin Wardrobe": active account preview | skin + cape tools | accounts.
 */
@Composable
internal fun VoltWardrobe(
    accounts: List<LauncherAccount>,
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    config: HorizonConfigState,
    skinReloadCounter: Int,
    onPickSkinFile: () -> Unit,
    onSkinUpdated: () -> Unit,
    onAccountsChange: (List<LauncherAccount>) -> Unit,
    accent: Color = Color(0xFFFFB454),
    onAccent: Color = Color(0xFF2A1700),
    cardColor: Color = Color(0xFF161B2E),
    rowColor: Color = Color(0xFF1E2438)
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalWAccent provides accent, LocalWOnAccent provides onAccent, LocalWCard provides cardColor, LocalWRow provides rowColor
    ) {
    VoltWardrobeBody(accounts, activeAccount, activeSkinBitmap, config, skinReloadCounter, onPickSkinFile, onSkinUpdated, onAccountsChange)
    }
}

@Composable
private fun VoltWardrobeBody(
    accounts: List<LauncherAccount>,
    activeAccount: LauncherAccount,
    activeSkinBitmap: Bitmap,
    config: HorizonConfigState,
    skinReloadCounter: Int,
    onPickSkinFile: () -> Unit,
    onSkinUpdated: () -> Unit,
    onAccountsChange: (List<LauncherAccount>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountsNow by rememberUpdatedState(accounts)
    val configNow by rememberUpdatedState(config)

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var linkInput by remember { mutableStateOf("") }
    var lookupSource by remember { mutableStateOf(SkinTools.Source.ELYBY) }
    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(AccountType.OFFLINE) }
    var capeTick by remember { mutableStateOf(0) }

    val capeBitmap = remember(activeAccount.capePath, capeTick) { SkinTools.loadCapeBitmap(activeAccount.capePath) }

    fun say(msg: String) {
        status = msg
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun mutate(id: String, f: (LauncherAccount) -> LauncherAccount) {
        onAccountsChange(accountsNow.map { if (it.id == id) f(it) else it })
    }

    /** Runs blocking work off the main thread, then reports its message. */
    fun runBg(label: String, work: () -> FetchOutcome) {
        if (busy != null) return
        busy = label
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching(work).getOrElse { FetchOutcome(activeAccount, "Failed: ${it.message}", false) }
            }
            busy = null
            if (r.ok) {
                mutate(r.account.id) { r.account }
                onSkinUpdated()
                capeTick++
            }
            say(r.message)
        }
    }

    fun fetchByName(name: String, source: SkinTools.Source, target: LauncherAccount, wantSkin: Boolean, wantCape: Boolean): FetchOutcome {
        val t = SkinTools.lookup(name, source)
            ?: return FetchOutcome(target, "No skin or cape found for '$name' on ${source.label} (or no connection).", false)
        var acc = target
        val got = mutableListOf<String>()
        if (wantSkin && t.skin != null) {
            val r = SkinTools.importSkinBytes(context, t.skin, acc, configNow, name)
            if (r.success && r.skinFile != null) {
                acc = acc.copy(skinPath = r.skinFile.absolutePath, slimSkinModel = r.slimModel, skinSourceLabel = "${t.source}: ${t.name}")
                got += "skin"
            }
        }
        if (wantCape && t.cape != null) {
            val f = SkinTools.saveCape(context, acc.id, t.cape)
            if (f != null) {
                acc = acc.copy(capePath = f.absolutePath)
                got += "cape"
            }
        }
        return if (got.isEmpty()) FetchOutcome(target, "'${t.name}' on ${t.source} has no ${if (wantSkin && wantCape) "skin or cape" else if (wantSkin) "skin" else "cape"}.", false)
        else FetchOutcome(acc, "Applied ${got.joinToString(" + ")} from ${t.source} (${t.name}).", true)
    }

    val capePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val acc = activeAccount
            runBg("Importing cape…") {
                val f = SkinTools.saveCapeFromUri(context, acc.id, uri)
                if (f != null) FetchOutcome(acc.copy(capePath = f.absolutePath), "Cape imported.", true)
                else FetchOutcome(acc, "That image isn't a valid cape (needs a Minecraft cape PNG, e.g. 64×32).", false)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------------------------------------------------------- Card 1: active account
        WCard(Modifier.weight(0.95f)) {
            WTitle("Active Account")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VoltGrayRoot)
            ) {
                MinecraftSkin3DPreviewCanvas(
                    skinBitmap = activeSkinBitmap,
                    slimArms = activeAccount.slimSkinModel,
                    autoRotate = true,
                    walkAnimation = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MinecraftSkinHeadAvatar(skinBitmap = activeSkinBitmap, size = 38.dp, cornerRadius = 9.dp)
                Column(Modifier.weight(1f)) {
                    Text(activeAccount.username, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(activeAccount.accountType.badge, fontSize = 12.sp, color = VoltMutedText)
                }
            }
            Text("Skin: ${activeAccount.skinSourceLabel}", fontSize = 12.sp, color = VoltMutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (activeAccount.capePath.isBlank()) "Cape: none" else "Cape: equipped",
                fontSize = 12.sp, color = VoltMutedText
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WChip("Wide", !activeAccount.slimSkinModel, Modifier.weight(1f)) {
                    mutate(activeAccount.id) { it.copy(slimSkinModel = false) }; onSkinUpdated()
                }
                WChip("Slim", activeAccount.slimSkinModel, Modifier.weight(1f)) {
                    mutate(activeAccount.id) { it.copy(slimSkinModel = true) }; onSkinUpdated()
                }
            }
        }

        // ---------------------------------------------------------------- Card 2: skin + cape tools
        WCard(Modifier.weight(1.35f)) {
            WTitle("Skin & Capes")

            WLabel("Skin")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                WButton("Import from Files", Icons.Default.Folder, primary = true, modifier = Modifier.weight(1f)) { onPickSkinFile() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WField(linkInput, { linkInput = it }, "Skin image link (https://…png)", Modifier.weight(1f))
                WButton("Fetch", Icons.Default.Link) {
                    val url = linkInput.trim()
                    val acc = activeAccount
                    if (url.isEmpty()) say("Paste a link to a skin PNG first.")
                    else runBg("Downloading skin…") {
                        val bytes = SkinTools.fetchSkinUrl(url)
                            ?: return@runBg FetchOutcome(acc, "Couldn't download a PNG from that link.", false)
                        val r = SkinTools.importSkinBytes(context, bytes, acc, configNow, "link")
                        if (r.success && r.skinFile != null)
                            FetchOutcome(acc.copy(skinPath = r.skinFile.absolutePath, slimSkinModel = r.slimModel, skinSourceLabel = "Link"), "Skin imported from link.", true)
                        else FetchOutcome(acc, r.message, false)
                    }
                }
            }

            WLabel("Get skin & cape by player name")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WField(nameInput, { nameInput = it }, "Player name", Modifier.weight(1f))
                SkinTools.Source.entries.filter { it != SkinTools.Source.URL }.forEach { s ->
                    WChip(s.label, lookupSource == s) { lookupSource = s }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                WButton("Skin + Cape", Icons.Default.Download, primary = true, modifier = Modifier.weight(1f)) {
                    val n = nameInput.trim(); val acc = activeAccount; val src = lookupSource
                    if (n.isEmpty()) say("Enter a player name first.")
                    else runBg("Looking up $n…") { fetchByName(n, src, acc, wantSkin = true, wantCape = true) }
                }
                WButton("Cape only", Icons.Default.Download, modifier = Modifier.weight(1f)) {
                    val n = nameInput.trim(); val acc = activeAccount; val src = lookupSource
                    if (n.isEmpty()) say("Enter a player name first.")
                    else runBg("Looking up $n…") { fetchByName(n, src, acc, wantSkin = false, wantCape = true) }
                }
            }

            WLabel("Cape")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CapeThumb(null, selected = activeAccount.capePath.isBlank(), label = "None") {
                    val acc = activeAccount
                    SkinTools.deleteCape(acc)
                    mutate(acc.id) { it.copy(capePath = "") }
                    capeTick++
                    say("Cape removed.")
                }
                SkinTools.CAPE_PRESETS.forEach { p ->
                    val preview = remember(p.id) { SkinTools.generateCapePreset(p.id) }
                    CapeThumb(preview, selected = false, label = p.title) {
                        val acc = activeAccount
                        runBg("Applying cape…") {
                            val f = SkinTools.savePresetCape(context, acc.id, p.id)
                            if (f != null) FetchOutcome(acc.copy(capePath = f.absolutePath), "${p.title} cape equipped.", true)
                            else FetchOutcome(acc, "Couldn't create the cape.", false)
                        }
                    }
                }
                capeBitmap?.let { CapeThumb(it, selected = true, label = "Current") {} }
            }
            WButton("Import cape image", Icons.Default.Folder, modifier = Modifier.fillMaxWidth()) { capePicker.launch("image/*") }

            val s = busy ?: status
            if (s != null) Text(s, fontSize = 12.sp, color = if (busy != null) WGreen else VoltMutedText)
            Text(
                "Capes show in-game for your own player. Ely.by/Mojang are looked up by name (no password needed).",
                fontSize = 11.sp, lineHeight = 15.sp, color = VoltMutedText
            )
        }

        // ---------------------------------------------------------------- Card 3: accounts
        WCard(Modifier.weight(1.1f)) {
            WTitle("Accounts")
            WField(newName, { newName = it }, "Player name", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountType.entries.forEach { t ->
                    WChip(t.badge, newType == t, Modifier.weight(1f)) { newType = t }
                }
            }
            if (newType == AccountType.MICROSOFT) {
                Text("Microsoft sign-in isn't built yet – this saves a Microsoft-labelled profile (offline play).", fontSize = 11.sp, color = VoltMutedText)
            }
            WButton("Add account", Icons.Default.PersonAdd, primary = true, modifier = Modifier.fillMaxWidth()) {
                val n = newName.trim()
                if (n.isEmpty()) {
                    say("Enter a player name first.")
                } else {
                    val added = LauncherAccount(
                        id = UUID.randomUUID().toString(),
                        username = n,
                        uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$n".toByteArray()).toString(),
                        accountType = newType,
                        isActive = true
                    )
                    onAccountsChange(accountsNow.map { it.copy(isActive = false) } + added)
                    newName = ""
                    if (newType == AccountType.ELYBY) {
                        runBg("Fetching $n from Ely.by…") { fetchByName(n, SkinTools.Source.ELYBY, added, wantSkin = true, wantCape = true) }
                    } else say("Added $n.")
                }
            }

            accounts.forEach { acc ->
                val bmp = remember(acc.id, acc.skinPath, acc.slimSkinModel, skinReloadCounter) {
                    HorizonNativeBridge.loadOrGenerateAccountSkinBitmap(context, acc)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (acc.isActive) WRowBg else WCardBg,
                    border = BorderStroke(1.dp, if (acc.isActive) WGreen.copy(alpha = 0.55f) else VoltGrayStroke),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animatedPressScale {
                            if (!acc.isActive) onAccountsChange(accountsNow.map { it.copy(isActive = it.id == acc.id) })
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MinecraftSkinHeadAvatar(skinBitmap = bmp, size = 36.dp, cornerRadius = 8.dp)
                        Column(Modifier.weight(1f)) {
                            Text(acc.username, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(acc.accountType.badge + if (acc.isActive) " · Active" else "", fontSize = 11.sp, color = if (acc.isActive) WGreen else VoltMutedText)
                        }
                        if (accounts.size > 1) {
                            Icon(
                                Icons.Default.Delete, contentDescription = "Delete account", tint = WDanger,
                                modifier = Modifier
                                    .size(24.dp)
                                    .animatedPressScale {
                                        SkinTools.deleteCape(acc)
                                        val rest = accountsNow.filterNot { it.id == acc.id }
                                        onAccountsChange(if (rest.none { it.isActive }) rest.mapIndexed { i, a -> a.copy(isActive = i == 0) } else rest)
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- small building blocks

@Composable
private fun WCard(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WCardBg,
        border = BorderStroke(1.dp, VoltGrayStroke),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) { content() }
    }
}

@Composable
private fun WTitle(text: String) = Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)

@Composable
private fun WLabel(text: String) = Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VoltMutedText)

@Composable
private fun WChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) WGreen.copy(alpha = 0.18f) else WRowBg,
        border = BorderStroke(1.dp, if (selected) WGreen.copy(alpha = 0.6f) else VoltGrayStroke),
        modifier = modifier.animatedPressScale { onClick() }
    ) {
        Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (selected) WGreen else Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun WButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (primary) WGreen else WRowBg,
        border = if (primary) null else BorderStroke(1.dp, VoltGrayStroke),
        modifier = modifier.animatedPressScale { onClick() }
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = if (primary) WOnGreen else Color.White, modifier = Modifier.size(18.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (primary) WOnGreen else Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun WField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = VoltGrayRoot,
        border = BorderStroke(1.dp, VoltGrayStroke),
        modifier = modifier.height(42.dp)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = VoltMutedText, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(WGreen),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CapeThumb(cape: Bitmap?, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 58.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(VoltGrayRoot)
                .border(1.dp, if (selected) WGreen else VoltGrayStroke, RoundedCornerShape(5.dp))
                .animatedPressScale { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (cape == null) {
                Text("✕", fontSize = 12.sp, color = VoltMutedText)
            } else {
                val face = remember(cape) { SkinTools.capeFace(cape).asImageBitmap() }
                Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                    drawImage(
                        image = face,
                        srcOffset = IntOffset(0, 0),
                        srcSize = IntSize(face.width, face.height),
                        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.None
                    )
                }
            }
        }
        Text(label, fontSize = 10.sp, color = VoltMutedText, maxLines = 1)
    }
}
