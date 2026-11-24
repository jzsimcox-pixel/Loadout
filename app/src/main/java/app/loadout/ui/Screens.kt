package app.loadout.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loadout.data.Catalog
import app.loadout.data.Emulator
import app.loadout.data.SystemRecipe
import app.loadout.data.ThemeOption
import app.loadout.debug.LinkChecks
import app.loadout.debug.LinkStep
import app.loadout.debug.LinkSteps
import app.loadout.debug.PairState
import app.loadout.device.DeviceInfo
import app.loadout.install.AppMatcher
import app.loadout.install.EsdeView
import app.loadout.install.InstallState
import app.loadout.install.SettingsView
import app.loadout.install.ThemeState
import app.loadout.storage.CardScan
import app.loadout.storage.EsdeRow
import app.loadout.storage.EsdeStatus
import app.loadout.storage.FolderPlanner
import app.loadout.storage.SettingChange
import app.loadout.storage.SettingsRow
import app.loadout.storage.SettingsStatus
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
    logos: Map<String, ImageBitmap>,
    logoNote: String,
    onGetLogos: (() -> Unit)?,
    onToggle: (String) -> Unit,
    onDetails: (String) -> Unit,
    onContinue: () -> Unit,
) {
    // Systems in the setup come first, then the rest, each group in alphabetical order.
    // The order is fixed when the screen opens so tiles do not jump while you toggle them.
    val ordered = remember(catalog) {
        catalog.systems.sortedWith(
            compareByDescending<SystemRecipe> { it.id in selected }.thenBy { it.name.lowercase() },
        )
    }
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
            Column(modifier = Modifier.weight(1f)) {
                Label("Pick your systems", size = 24.sp, weight = FontWeight.Bold)
                Label(
                    "Systems with games on your card start selected.",
                    size = 13.sp,
                    color = Palette.Muted,
                )
                if (logoNote.isNotBlank()) {
                    Label(logoNote, size = 11.sp, color = Palette.Muted, maxLines = 1)
                }
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
            itemsIndexed(ordered, key = { _, item -> item.id }) { index, recipe ->
                SystemTile(
                    recipe = recipe,
                    logo = logos[recipe.id],
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
            if (onGetLogos != null) {
                ActionButton(text = "Get logos", onClick = onGetLogos)
            }
            ActionButton(text = "Continue", onClick = onContinue, primary = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemTile(
    recipe: SystemRecipe,
    logo: ImageBitmap?,
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
            .height(106.dp)
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
            verticalAlignment = Alignment.Top,
        ) {
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = recipe.name,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.IconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    SystemIcon(recipe.kind, size = 20.dp)
                }
            }
            if (on) {
                Spacer(Modifier.width(6.dp))
                CheckMark()
            }
        }
        Column {
            if (logo == null) {
                Label(recipe.code, size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
            }
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
    // The download page is offered when Loadout cannot download the emulator, or just failed to.
    val showPageButton = source != null && source.url.isNotBlank() && installState !is InstallState.Installed &&
        (!source.downloadable || installState is InstallState.Failed)

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
                            primary = !showInstallButton,
                            modifier = if (showInstallButton) Modifier else Modifier.focusRequester(primaryFocus),
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
    onEsde: () -> Unit,
    onSettings: () -> Unit,
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
                ActionButton(text = "Set up ES-DE", onClick = onEsde)
                ActionButton(text = "Emulator settings", onClick = onSettings)
                ActionButton(text = "Back", onClick = onBack)
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

// ---------------------------------------------------------------- Theme

@Composable
fun ThemeScreen(
    themes: List<ThemeOption>,
    state: ThemeState,
    onPick: (ThemeOption) -> Unit,
    onContinue: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state.working, state is ThemeState.Done) { requestFocusSafely(firstFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar("Choose an ES-DE theme", "Optional")
        Label(
            text = when (state) {
                ThemeState.Idle ->
                    "Loadout downloads the theme into ES-DE's themes folder and uses its system logos. ES-DE can use it too."
                is ThemeState.Downloading ->
                    "Downloading… ${state.megabytes} MB so far. Large themes take a few minutes."
                ThemeState.Unpacking -> "Unpacking the theme…"
                is ThemeState.Done -> "${state.name} is installed."
                is ThemeState.Failed -> "That did not work: ${state.message}"
            },
            size = 13.sp,
            color = if (state is ThemeState.Failed) Palette.Warn else Palette.Muted,
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
            if (!state.working && state !is ThemeState.Done) {
                themes.forEachIndexed { index, option ->
                    SetupRow(
                        name = option.name,
                        value = if (option.sizeMb > 0) "about ${option.sizeMb} MB" else "size unknown",
                        onClick = { onPick(option) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
        BottomBar {
            if (!state.working && state !is ThemeState.Done) KeyHint("A", "Download")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            if (!state.working) {
                ActionButton(
                    text = if (state is ThemeState.Done) "Continue" else "Skip",
                    onClick = onContinue,
                    primary = state is ThemeState.Done,
                    modifier = if (state is ThemeState.Done) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- ES-DE

@Composable
fun EsdeScreen(
    view: EsdeView,
    message: String?,
    onApply: (List<EsdeRow>) -> Unit,
    onUseTheme: (String) -> Unit,
    onBack: () -> Unit,
) {
    val rows = (view as? EsdeView.Ready)?.rows.orEmpty()
    // Changes that would replace a choice made inside ES-DE start switched off.
    var included by remember(rows) {
        mutableStateOf(rows.filter { it.status == EsdeStatus.CHANGE && !it.replacesUserChoice }.map { it.systemId }.toSet())
    }
    val toApply = rows.filter { it.systemId in included }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(view) { requestFocusSafely(firstFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar("Set up ES-DE", if (view is EsdeView.Ready) "${toApply.size} to change" else "")
        Label(
            text = message ?: when (view) {
                EsdeView.Loading -> "Reading ES-DE's setup…"
                EsdeView.NotInstalled -> "ES-DE isn't installed on this device. Install it, open it once, then come back."
                EsdeView.NoDataFolder ->
                    "ES-DE hasn't been set up yet. Open ES-DE once, choose its data folder, close it, then come back."
                EsdeView.NoSystemList -> "Loadout couldn't read ES-DE's list of emulators, so it won't change anything."
                is EsdeView.Ready ->
                    "Close ES-DE first, or it may undo these changes. Each file is backed up before it is edited. Press A on a row to include or skip it."
            },
            size = 13.sp,
            color = if (message != null) Palette.Text else Palette.Muted,
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
            rows.forEachIndexed { index, row ->
                val current = row.current.ifBlank { "its default" }
                val text = when (row.status) {
                    EsdeStatus.ALREADY -> "${row.emulator} · already set"
                    EsdeStatus.NOT_LISTED -> "ES-DE doesn't list ${row.emulator} · stays on $current"
                    EsdeStatus.NO_SYSTEM -> "ES-DE has no entry for this system"
                    EsdeStatus.CHANGE ->
                        if (row.systemId in included) "$current → ${row.emulator} · will change"
                        else "stays on $current · A to switch to ${row.emulator}"
                    EsdeStatus.ADDABLE ->
                        if (row.systemId in included) "add ${row.emulator} to ES-DE and switch · will change"
                        else "ES-DE doesn't list ${row.emulator} · A to add it and switch"
                }
                SetupRow(
                    name = row.title,
                    value = text,
                    onClick = {
                        if (row.status == EsdeStatus.CHANGE || row.status == EsdeStatus.ADDABLE) {
                            included = if (row.systemId in included) included - row.systemId else included + row.systemId
                        }
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        BottomBar {
            KeyHint("A", "Include or skip")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            val offer = (view as? EsdeView.Ready)?.themeOffer
            if (offer != null) {
                ActionButton(text = "Use downloaded theme", onClick = { onUseTheme(offer) })
            }
            if (toApply.isNotEmpty()) {
                ActionButton(
                    text = if (toApply.size == 1) "Apply 1 change" else "Apply ${toApply.size} changes",
                    onClick = { onApply(toApply) },
                    primary = true,
                )
            }
            ActionButton(
                text = "Back",
                onClick = onBack,
                modifier = if (rows.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

// ---------------------------------------------------------------- Emulator settings

private fun shorten(value: String): String = if (value.length > 38) value.take(37) + "…" else value

private fun changeText(change: SettingChange): String {
    val old = change.old
    return if (old == null) {
        "${change.key}: ${shorten(change.new)} (added)"
    } else {
        "${change.key}: ${shorten(old.ifEmpty { "empty" })} → ${shorten(change.new)}"
    }
}

private fun plural(count: Int, one: String, many: String): String = if (count == 1) "1 $one" else "$count $many"

@Composable
fun SettingsScreen(
    view: SettingsView,
    message: String?,
    onApply: (List<SettingsRow>) -> Unit,
    onSetUpLink: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = view as? SettingsView.Ready
    val rows = ready?.rows.orEmpty()
    var skipped by remember(rows) { mutableStateOf(emptySet<String>()) }
    val toApply = rows.filter { it.status == SettingsStatus.READY && it.emulator !in skipped }
    val changeCount = toApply.sumOf { it.changes.size }
    // Offer the wireless debugging guide when something sits in a folder Android keeps closed.
    val needsLink = ready != null && !ready.linked && rows.any { it.status == SettingsStatus.LOCKED || it.lockedFiles > 0 }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(view) { requestFocusSafely(firstFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar("Emulator settings", if (ready != null) "$changeCount to change" else "")
        Label(
            text = message ?: when (view) {
                SettingsView.Loading -> "Comparing your emulators' settings…"
                SettingsView.NoTemplates -> "Loadout couldn't read its settings templates, so it won't change anything."
                is SettingsView.Ready ->
                    (if (view.tunedOn != null) "These settings were tuned on the ${view.tunedOn}; controls may differ here. " else "") +
                        (if (view.linked) "Wireless debugging is connected. " else "") +
                        "Close the emulators first, or they may undo these changes. Each file is backed up before it is edited. Press A on a row to include or skip it."
            },
            size = 13.sp,
            color = if (message != null) Palette.Text else Palette.Muted,
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
            rows.forEachIndexed { index, row ->
                val included = row.emulator !in skipped
                val locked = if (row.lockedFiles > 0) " · ${plural(row.lockedFiles, "file needs", "files need")} wireless debugging" else ""
                val unread = (if (row.unreadable > 0) " · ${plural(row.unreadable, "file", "files")} left alone, unreadable" else "") +
                    (if (row.notCreated > 0) " · ${plural(row.notCreated, "file", "files")} not created by the emulator yet" else "")
                val text = when (row.status) {
                    SettingsStatus.NOT_INSTALLED -> "not installed"
                    SettingsStatus.NO_FOLDER -> "open it once so it creates its settings, then come back$unread"
                    SettingsStatus.LOCKED -> "Android keeps this folder closed · needs wireless debugging"
                    SettingsStatus.MATCHES -> "already matches$locked$unread"
                    SettingsStatus.READY ->
                        if (included) "${plural(row.changes.size, "setting", "settings")} will change$locked$unread"
                        else "skipped · A to include"
                }
                SetupRow(
                    name = row.emulator,
                    value = text,
                    onClick = {
                        if (row.status == SettingsStatus.READY) {
                            skipped = if (included) skipped + row.emulator else skipped - row.emulator
                        }
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
                if (row.status == SettingsStatus.READY && included) {
                    val shown = row.changes.take(6)
                    for (change in shown) {
                        Label(
                            changeText(change),
                            size = 12.sp,
                            color = Palette.Muted,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 22.dp, end = 10.dp, bottom = 4.dp),
                        )
                    }
                    if (row.changes.size > shown.size) {
                        Label(
                            "and ${row.changes.size - shown.size} more",
                            size = 12.sp,
                            color = Palette.Muted,
                            modifier = Modifier.padding(start = 22.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
        BottomBar {
            KeyHint("A", "Include or skip")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            if (needsLink) {
                ActionButton(text = "Set up wireless debugging", onClick = onSetUpLink, primary = toApply.isEmpty())
            }
            if (toApply.isNotEmpty()) {
                ActionButton(
                    text = "Apply ${plural(changeCount, "change", "changes")}",
                    onClick = { onApply(toApply) },
                    primary = true,
                )
            }
            ActionButton(
                text = "Back",
                onClick = onBack,
                modifier = if (rows.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

// ---------------------------------------------------------------- Wireless debugging guide

private val linkStepNames = listOf(
    LinkStep.NOTIFICATIONS to "Notifications",
    LinkStep.DEVELOPER to "Developer options",
    LinkStep.WIFI to "Wi-Fi",
    LinkStep.WIRELESS to "Wireless debugging",
    LinkStep.PAIR to "Pairing code",
    LinkStep.CONNECT to "Connect",
)

private fun linkHint(step: LinkStep, checks: LinkChecks, pairState: PairState, canToggle: Boolean): String = when (step) {
    LinkStep.DEVELOPER ->
        "Android hides Wireless debugging until Developer options are on. Press the button, find \"Build number\" and tap it 7 times until Android says you are a developer. " +
            "A Loadout notification repeats this while you are there; tap it to come back."
    LinkStep.WIFI -> "Wireless debugging only works on Wi-Fi. Press the button, connect to your Wi-Fi, then tap Loadout's notification to come back."
    LinkStep.WIRELESS ->
        if (canToggle) {
            "Loadout can switch Wireless debugging on for you."
        } else {
            "Press the button. Loadout opens Developer options at Wireless debugging: switch it on. When Android asks, tick \"Always allow on this network\" and tap Allow. Then tap Loadout's notification to come back."
        }
    LinkStep.NOTIFICATIONS ->
        "Some steps happen in Android's own settings, where Loadout can't show its screen. It guides you there with notifications, and asks for the pairing code in one. Allow notifications to continue."
    LinkStep.PAIR -> when (pairState) {
        is PairState.Failed -> "Pairing didn't finish: ${pairState.message} Press the button to try again."
        PairState.Searching, PairState.CodeNeeded, PairState.Pairing ->
            "Pairing is waiting. Open Wireless debugging in Developer options and tap \"Pair device with pairing code\", then answer Loadout's notification."
        else ->
            "Press the button. In Developer options, tap the words \"Wireless debugging\" (not the switch) to open it, then tap \"Pair device with pairing code\". Android shows a 6-digit code. " +
                "Swipe down from the top of the screen, tap \"Enter pairing code\" on Loadout's notification and type the code. Leave Android's code screen open while you type. Each of these is repeated in the notification."
    }
    LinkStep.CONNECT -> checks.error ?: "Connecting…"
    LinkStep.DONE ->
        "Connected. Loadout can now reach every emulator's settings." +
            (if (canToggle) " It will switch Wireless debugging off again when you leave Emulator settings." else " Loadout couldn't get permission to switch Wireless debugging off by itself, so switch it off in Developer options when you are done.")
}

private fun linkButton(step: LinkStep, canToggle: Boolean): String = when (step) {
    LinkStep.DEVELOPER -> "Open About"
    LinkStep.WIFI -> "Open Wi-Fi settings"
    LinkStep.WIRELESS -> if (canToggle) "Turn it on" else "Open Developer options"
    LinkStep.NOTIFICATIONS -> "Allow notifications"
    LinkStep.PAIR -> "Start pairing"
    LinkStep.CONNECT -> "Try again"
    LinkStep.DONE -> "Done"
}

@Composable
fun LinkScreen(
    checks: LinkChecks?,
    pairState: PairState,
    busy: Boolean,
    canToggle: Boolean,
    notDoneYet: Boolean,
    onAction: (LinkStep) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val step = checks?.let { LinkSteps.next(it) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(step) { requestFocusSafely(firstFocus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar("Wireless debugging", if (step == LinkStep.DONE) "Connected" else "One-time setup")
        Label(
            text = "This lets Loadout reach the settings folders Android keeps closed to other apps. You set it up once per device.",
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
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (checks == null || step == null) {
                Label("Checking this device…", size = 14.sp, color = Palette.Muted, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                val position = linkStepNames.indexOfFirst { it.first == step }.let { if (it < 0) linkStepNames.size else it }
                linkStepNames.forEachIndexed { index, (_, name) ->
                    val state = when {
                        index < position -> "done"
                        index == position -> if (busy) "checking…" else "do this now"
                        else -> "later"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label(
                            "${index + 1}. $name",
                            size = 14.sp,
                            weight = if (index == position) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (index > position) Palette.Muted else Palette.Text,
                        )
                        Label(
                            state,
                            size = 13.sp,
                            color = if (index == position) Palette.Accent else Palette.Muted,
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(Palette.Border),
                )
                if (notDoneYet && !busy) {
                    Label(
                        "Android still shows this step as not done. Try it once more.",
                        size = 14.sp,
                        color = Palette.Warn,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Label(linkHint(step, checks, pairState, canToggle), size = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        BottomBar {
            KeyHint("A", "Select")
            KeyHint("B", "Back")
            Spacer(Modifier.weight(1f))
            if (step != null && step != LinkStep.DONE) {
                ActionButton(text = "Check again", onClick = onRefresh)
            }
            if (step != null) {
                ActionButton(
                    text = linkButton(step, canToggle),
                    onClick = { if (step == LinkStep.DONE) onBack() else onAction(step) },
                    primary = true,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            ActionButton(
                text = "Back",
                onClick = onBack,
                modifier = if (step == null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

// ---------------------------------------------------------------- Crash report

@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSafely(focus) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar("Loadout closed unexpectedly last time", "")
        Label("Nothing was lost. A photo of this screen helps find the cause.", size = 13.sp, color = Palette.Muted)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Label(report, size = 11.sp)
        }
        BottomBar {
            Spacer(Modifier.weight(1f))
            ActionButton(text = "Continue", onClick = onContinue, primary = true, modifier = Modifier.focusRequester(focus))
        }
    }
}
