package app.loadout.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import app.loadout.data.Emulator
import app.loadout.data.SystemRecipe
import app.loadout.device.DeviceInfo
import app.loadout.install.AppMatcher
import app.loadout.install.InstallState
import app.loadout.storage.CardScan
import app.loadout.storage.FolderPlanner
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
                        ActionButton(text = "Skip scan", onClick = onContinue)
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
    chosen: Emulator,
    onChoose: (Emulator) -> Unit,
    installState: InstallState,
    notice: String?,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    onCancelInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onToggle: () -> Unit,
    onBack: () -> Unit,
) {
    val games = scan?.gamesFor(recipe.id) ?: 0
    val folder = scan?.folderFor(recipe.id)
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSafely(primaryFocus) }
    // An emulator that another source keeps updated gets no install button: Loadout leaves it alone.
    val managedElsewhere = installState is InstallState.Installed && installState.managedBy != null
    val source = chosen.source
    val showInstallButton = source?.downloadable == true && !managedElsewhere
    val showPageButton = source?.type == "page" && installState !is InstallState.Installed && !showInstallButton

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
                StatusLine(
                    installState is InstallState.Installed,
                    if (installState is InstallState.Installed) {
                        "${AppMatcher.baseName(chosen.name)} installed"
                    } else {
                        "${AppMatcher.baseName(chosen.name)} not found"
                    },
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
                for (option in listOf(recipe.emulator) + recipe.alternates) {
                    val inUse = option.name == chosen.name
                    val kind = if (option.name == recipe.emulator.name) "Recommended" else "Alternate"
                    EmulatorRow(
                        name = option.name,
                        tag = if (inUse) "$kind · In use" else kind,
                        highlighted = inUse,
                        onClick = { onChoose(option) },
                    )
                }
                if (recipe.alternates.isNotEmpty()) {
                    Label("Move up and press A on an emulator to use it for this system.", size = 12.sp, color = Palette.Muted)
                }
                Label(
                    installStatusText(chosen, installState),
                    size = 13.sp,
                    color = if (installState is InstallState.Failed) Palette.Warn else Palette.Text,
                )
                if (notice != null) {
                    Label("Update check failed: $notice", size = 13.sp, color = Palette.Warn)
                }
                Label(supportNote(recipe.support), size = 13.sp, color = Palette.Muted)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showInstallButton) {
                        ActionButton(
                            text = when (installState) {
                                InstallState.NeedsPermission -> "Allow installs"
                                InstallState.Checking, is InstallState.Downloading -> "Working…"
                                InstallState.WaitingForApproval -> "Try again"
                                is InstallState.Installed -> "Check for update"
                                else -> "Install ${chosen.name}"
                            },
                            onClick = when {
                                installState == InstallState.NeedsPermission -> onAllowInstalls
                                installState.busy -> ({})
                                else -> onInstall
                            },
                            primary = installState !is InstallState.Installed && !installState.busy,
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    }
                    if (installState == InstallState.WaitingForApproval) {
                        ActionButton(text = "Cancel install", onClick = onCancelInstall)
                    }
                    if (showPageButton) {
                        ActionButton(
                            text = "Open download page",
                            onClick = { onOpenPage(source?.url.orEmpty()) },
                            primary = true,
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    }
                    ActionButton(
                        text = "Back",
                        onClick = onBack,
                        modifier = if (showInstallButton || showPageButton) Modifier else Modifier.focusRequester(primaryFocus),
                    )
                    ActionButton(
                        text = if (on) "Remove from setup" else "Add to setup",
                        onClick = onToggle,
                        textColor = if (on) Palette.Warn else Palette.Text,
                    )
                }
            }
        }
        BottomBar {
            KeyHint("A", "Select")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            Label("Removing a system keeps your games and saves.", size = 13.sp, color = Palette.Muted)
        }
    }
}

private fun installStatusText(emulator: Emulator, state: InstallState): String = when (state) {
    InstallState.Idle ->
        if (emulator.source == null) {
            "Loadout can't install ${emulator.name} yet. Its download source hasn't been set up."
        } else if (!emulator.source.downloadable) {
            "Not found on this device. Loadout can't download ${AppMatcher.baseName(emulator.name)} itself yet. Open its official download page, install it, then come back."
        } else {
            "Press Install to check. If ${emulator.name} is already on this device and current, nothing is changed."
        }
    InstallState.NeedsPermission ->
        "Android needs your OK before Loadout can install apps. Press Allow installs, switch it on, come back, then press Install."
    InstallState.Checking -> "Checking the latest release…"
    is InstallState.Downloading -> "Downloading… ${state.percent}%"
    InstallState.WaitingForApproval ->
        "Waiting for you to approve the install. If no prompt is showing, press Try again."
    is InstallState.Installed -> when (state.managedBy) {
        null -> "Installed · version ${state.version}"
        "the Play Store" -> "Installed · version ${state.version} · the Play Store keeps it updated, so Loadout leaves it alone."
        "its own updater" -> "Installed · version ${state.version} · it updates itself from inside the app, so Loadout leaves updates to it."
        else -> "Installed · version ${state.version} · it came from another source, so Loadout leaves it alone."
    }
    is InstallState.Failed -> state.message
}

private fun supportNote(support: String): String = when (support) {
    "experimental" -> "Experimental on Android. Few games run well."
    "install-first" -> "Tuned settings for this system come later."
    else -> "Fully supported system."
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
private fun EmulatorRow(name: String, tag: String, highlighted: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        focused -> Palette.Text
        highlighted -> Palette.Accent
        else -> Palette.Border
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (highlighted) Palette.SurfaceOn else Palette.Surface)
            .border(if (focused) 3.dp else if (highlighted) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
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
    volume: VolumeScan?,
    selected: Set<String>,
    installStates: Map<String, InstallState>,
    emulatorFor: (SystemRecipe) -> Emulator,
    onOpen: (String) -> Unit,
    onFoldersCreated: () -> Unit,
    onBack: () -> Unit,
) {
    val scan = volume?.card
    val chosen = catalog.systems.filter { it.id in selected }
    val missing = remember(volume, selected) {
        if (volume != null) FolderPlanner.plan(volume.root, volume.card, catalog, selected) else emptyList()
    }
    var confirming by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(confirming) { requestFocusSafely(firstFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar(if (confirming) "Create folders" else "Your setup", "${chosen.size} systems")
        Label(
            text = when {
                confirming -> "Loadout will create these empty folders. Nothing that exists is changed."
                notice != null -> notice.orEmpty()
                else -> "Open a system to install its emulator. Systems without an installer yet are marked."
            },
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
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (confirming) {
                for (folder in missing) {
                    Label(
                        folder.absolutePath,
                        size = 13.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else {
                if (chosen.isEmpty()) {
                    Label(
                        "No systems selected. Go back and pick some.",
                        size = 14.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                chosen.forEachIndexed { index, recipe ->
                    val emulator = emulatorFor(recipe)
                    val source = emulator.source
                    val state = installStates[emulator.name] ?: InstallState.Idle
                    val emulatorText = when {
                        state is InstallState.Installed -> "installed"
                        state is InstallState.Failed -> "install failed"
                        state.busy -> "working…"
                        source == null -> "no installer yet"
                        !source.downloadable -> "not found"
                        else -> "not found"
                    }
                    val folderText = if (scan?.folderFor(recipe.id) != null) "folder found" else "folder missing"
                    SetupRow(
                        name = "${recipe.code} · ${recipe.name}",
                        value = "${AppMatcher.baseName(emulator.name)} · $emulatorText · $folderText",
                        onClick = { onOpen(recipe.id) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
        BottomBar {
            KeyHint("A", if (confirming) "Select" else "Open")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            if (confirming) {
                ActionButton(text = "Cancel", onClick = { confirming = false })
                ActionButton(
                    text = "Create ${missing.size} folders",
                    primary = true,
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = {
                        val failed = FolderPlanner.create(missing)
                        notice = if (failed.isEmpty()) {
                            "Created ${missing.size} folders."
                        } else {
                            "Could not create ${failed.size} of ${missing.size} folders. The card may be read-only."
                        }
                        confirming = false
                        onFoldersCreated()
                    },
                )
            } else {
                if (missing.isNotEmpty()) {
                    ActionButton(text = "Create ${missing.size} missing folders", onClick = { confirming = true })
                }
                ActionButton(text = "Back to systems", onClick = onBack)
            }
        }
    }
}

@Composable
private fun SetupRow(name: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .border(2.dp, if (focused) Palette.Text else Palette.Surface, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(name, size = 13.sp, color = Palette.Muted, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Label(value, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}
