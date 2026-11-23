package app.loadout.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loadout.data.Catalog
import app.loadout.data.SystemRecipe
import app.loadout.device.DeviceInfo
import app.loadout.storage.CardScan
import app.loadout.storage.Storage
import app.loadout.storage.VolumeScan

private val ScreenPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)

@Composable
private fun TopBar(left: String, right: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(left, size = 18.sp, weight = FontWeight.Bold)
        Label(right, size = 13.sp, color = Palette.Muted)
    }
}

@Composable
private fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.Border),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

private fun requestFocusSafely(requester: FocusRequester) {
    try {
        requester.requestFocus()
    } catch (_: IllegalStateException) {
        // The target is not on screen yet. Focus simply stays where it is.
    }
}

// ---------------------------------------------------------------- Detect

@Composable
fun DetectScreen(
    device: DeviceInfo,
    volume: VolumeScan?,
    hasAccess: Boolean,
    scanning: Boolean,
    onGrantAccess: () -> Unit,
    onRescan: () -> Unit,
    onContinue: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(hasAccess) { requestFocusSafely(primaryFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopBar("Loadout", "Step 1 · Detect")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Label("Found your handheld", size = 30.sp, weight = FontWeight.Bold)
                Label(
                    text = if (hasAccess) {
                        "Loadout read the device and the card. Check the details, then pick your systems."
                    } else {
                        "Loadout needs permission to read the card. It only looks. Nothing is moved or deleted."
                    },
                    size = 15.sp,
                    color = Palette.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hasAccess) {
                        ActionButton(
                            text = "Looks right, continue",
                            onClick = onContinue,
                            primary = true,
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                        ActionButton(text = "Rescan card", onClick = onRescan)
                    } else {
                        ActionButton(
                            text = "Allow file access",
                            onClick = onGrantAccess,
                            primary = true,
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                        ActionButton(text = "Continue without scanning", onClick = onContinue)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                InfoRow("Device", device.displayName)
                InfoRow("Screens", if (device.dualScreen) "Dual screen" else "Single screen")
                InfoRow("Chipset", device.chipset)
                InfoRow("Storage", storageSummary(volume, hasAccess, scanning))
                InfoRow("Games found", gamesSummary(volume, hasAccess, scanning), divider = false)
            }
        }
        BottomBar {
            KeyHint("A", "Select")
            KeyHint("B", "Back")
        }
    }
}

private fun storageSummary(volume: VolumeScan?, hasAccess: Boolean, scanning: Boolean): String = when {
    !hasAccess -> "Needs file access"
    scanning && volume == null -> "Scanning…"
    volume == null -> "Not scanned"
    else -> {
        val layout = if (volume.card.romsRoot != null) "ES-DE layout found" else "no ROMs folder yet"
        "${volume.label} · ${Storage.formatSize(volume.sizeBytes)} · $layout"
    }
}

private fun gamesSummary(volume: VolumeScan?, hasAccess: Boolean, scanning: Boolean): String = when {
    !hasAccess -> "Unknown"
    scanning && volume == null -> "Scanning…"
    volume == null -> "Unknown"
    else -> "${volume.card.totalGames} games in ${volume.card.systemsWithGames} systems"
}

// ---------------------------------------------------------------- Systems

@Composable
fun SystemsScreen(
    catalog: Catalog,
    scan: CardScan?,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDetails: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSafely(firstTile) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Label("Pick your systems", size = 24.sp, weight = FontWeight.Bold)
                Label(
                    "Systems with games on your card start selected.",
                    size = 13.sp,
                    color = Palette.Muted,
                )
            }
            Label("${selected.size} selected", size = 14.sp, weight = FontWeight.SemiBold)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(catalog.systems, key = { _, item -> item.id }) { index, recipe ->
                SystemTile(
                    recipe = recipe,
                    games = scan?.gamesFor(recipe.id) ?: 0,
                    on = recipe.id in selected,
                    onToggle = { onToggle(recipe.id) },
                    onDetails = { onDetails(recipe.id) },
                    modifier = if (index == 0) Modifier.focusRequester(firstTile) else Modifier,
                )
            }
        }
        BottomBar {
            KeyHint("A", "Toggle")
            KeyHint("X", "Details (or hold)")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            ActionButton(text = "Continue", onClick = onContinue, primary = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemTile(
    recipe: SystemRecipe,
    games: Int,
    on: Boolean,
    onToggle: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val borderColor = when {
        focused -> Palette.Text
        on -> Palette.Accent
        else -> Palette.Border
    }
    val note = when {
        games > 0 -> if (games == 1) "1 game" else "$games games"
        on -> "Folder will be created"
        else -> "Not selected"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.key == Key.ButtonX && event.type == KeyEventType.KeyUp) {
                    onDetails()
                    true
                } else {
                    false
                }
            }
            .clip(shape)
            .background(if (on) Palette.SurfaceOn else Palette.Surface)
            .border(if (focused) 3.dp else 2.dp, borderColor, shape)
            .combinedClickable(onClick = onToggle, onLongClick = onDetails)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.IconBg),
                contentAlignment = Alignment.Center,
            ) {
                SystemIcon(recipe.kind, size = 20.dp)
            }
            if (on) CheckMark()
        }
        Column {
            Label(recipe.code, size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
            Label(recipe.name, size = 11.sp, weight = FontWeight.Medium, maxLines = 1)
            Label(note, size = 10.sp, color = Palette.Muted, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Detail

@Composable
fun DetailScreen(
    recipe: SystemRecipe,
    scan: CardScan?,
    device: DeviceInfo,
    on: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
) {
    val games = scan?.gamesFor(recipe.id) ?: 0
    val folder = scan?.folderFor(recipe.id)
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSafely(primaryFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Systems / ${recipe.name}", "Loadout")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(230.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Label(recipe.code, size = 36.sp, weight = FontWeight.Bold)
                Label(
                    "${recipe.name} · ${if (games == 1) "1 game" else "$games games"}",
                    size = 13.sp,
                    color = Palette.Muted,
                )
                StatusLine(on, if (on) "In your setup" else "Not in your setup")
                StatusLine(
                    folder != null,
                    if (folder != null) "Folder found: ${folder.name}" else "Folder: ${recipe.folders.first()} (not on card)",
                )
                if (recipe.dualScreen) {
                    StatusLine(
                        device.dualScreen,
                        if (device.dualScreen) "Uses both screens here" else "Single-screen layout here",
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label("EMULATOR", size = 11.sp, color = Palette.Muted, weight = FontWeight.SemiBold)
                EmulatorRow(recipe.emulator.name, "Recommended", highlighted = true)
                for (alternate in recipe.alternates) {
                    EmulatorRow(alternate.name, "Alternate", highlighted = false)
                }
                Spacer(Modifier.height(4.dp))
                Label(supportNote(recipe.support), size = 13.sp, color = Palette.Muted)
                Label(
                    "Installing and updating emulators arrives in the next build.",
                    size = 13.sp,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        text = if (on) "Remove from setup" else "Add to setup",
                        onClick = onToggle,
                        primary = !on,
                        textColor = if (on) Palette.Warn else Palette.Text,
                        modifier = Modifier.focusRequester(primaryFocus),
                    )
                    ActionButton(text = "Back to systems", onClick = onBack)
                }
            }
        }
        BottomBar {
            KeyHint("A", "Select")
            KeyHint("B", "Back to systems")
            Spacer(Modifier.weight(1f))
            Label("Removing a system keeps your games and saves.", size = 13.sp, color = Palette.Muted)
        }
    }
}

private fun supportNote(support: String): String = when (support) {
    "experimental" -> "Experimental on Android. Loadout will install it and create folders, but few games run well."
    "install-first" -> "Loadout will install it and create folders. Tuned settings come later."
    else -> "Fully supported: install, folders and tuned settings."
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ok) {
            CheckMark(size = 15.dp)
        } else {
            Box(
                Modifier
                    .size(15.dp)
                    .padding(3.dp)
                    .border(2.dp, Palette.Muted, RoundedCornerShape(50)),
            )
        }
        Label(text, size = 13.sp, color = if (ok) Palette.Text else Palette.Muted)
    }
}

@Composable
private fun EmulatorRow(name: String, tag: String, highlighted: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) Palette.SurfaceOn else Palette.Surface)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) Palette.Accent else Palette.Border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(name, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
        Label(tag, size = 12.sp, color = if (highlighted) Palette.Accent else Palette.Muted)
    }
}

// ---------------------------------------------------------------- Review

@Composable
fun ReviewScreen(
    catalog: Catalog,
    scan: CardScan?,
    selected: Set<String>,
    onBack: () -> Unit,
) {
    val chosen = catalog.systems.filter { it.id in selected }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSafely(backFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar("Your setup", "${chosen.size} systems")
        Label(
            "This build stops here. Installing emulators and creating folders comes next. Nothing on the card has been changed.",
            size = 13.sp,
            color = Palette.Muted,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (chosen.isEmpty()) {
                InfoRow("No systems selected", "Go back and pick some", divider = false)
            }
            chosen.forEachIndexed { index, recipe ->
                val folder = scan?.folderFor(recipe.id)
                val folderText = if (folder != null) "folder found" else "folder to create"
                InfoRow(
                    name = "${recipe.code} · ${recipe.name}",
                    value = "${recipe.emulator.name} · $folderText",
                    divider = index != chosen.lastIndex,
                )
            }
        }
        BottomBar {
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            ActionButton(
                text = "Back to systems",
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocus),
            )
        }
    }
}
