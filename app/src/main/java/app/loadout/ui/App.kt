package app.loadout.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.loadout.data.Catalog
import app.loadout.data.Emulator
import app.loadout.data.SystemRecipe
import app.loadout.device.DeviceDetector
import app.loadout.install.EsdeHookup
import app.loadout.install.EsdeView
import app.loadout.install.InstallManager
import app.loadout.install.InstallState
import app.loadout.install.ThemeInstaller
import app.loadout.storage.EsdePick
import app.loadout.storage.EsdePlanner
import app.loadout.storage.LogoIndex
import app.loadout.storage.LogoRenderer
import app.loadout.storage.Prefs
import app.loadout.storage.Storage
import app.loadout.storage.VolumeScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Detect : Screen
    data object Systems : Screen
    data class Detail(val systemId: String, val fromReview: Boolean) : Screen
    data object Review : Screen
    data object Theme : Screen
    data object Esde : Screen
}

@Composable
fun LoadoutApp(catalog: Catalog, resumeCount: Int) {
    val context = LocalContext.current
    val device = remember { DeviceDetector.detect(context) }

    var screen by remember { mutableStateOf<Screen>(Screen.Detect) }
    var hasAccess by remember { mutableStateOf(Storage.hasAccess()) }
    var volume by remember { mutableStateOf<VolumeScan?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var rescanCount by remember { mutableIntStateOf(0) }
    // null means the user has never chosen, so the card scan decides the starting set.
    var selected by remember { mutableStateOf(Prefs.loadSelection(context)) }

    LaunchedEffect(resumeCount, rescanCount) {
        hasAccess = Storage.hasAccess()
        if (hasAccess) {
            scanning = true
            val result = withContext(Dispatchers.IO) { Storage.scan(context, catalog) }
            volume = result
            scanning = false
            if (selected == null) {
                selected = result.card.systems.filterValues { it.games > 0 }.keys.toSet()
            }
        }
    }

    val choices = remember { mutableStateMapOf<String, String>() }
    // Logos found on the device, by system id. Empty until the scan has run.
    var logos by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var logoNote by remember { mutableStateOf("") }
    var logosLoaded by remember { mutableStateOf(false) }
    var themeSkipped by remember { mutableStateOf(false) }
    var esdeView by remember { mutableStateOf<EsdeView>(EsdeView.Loading) }
    var esdeMessage by remember { mutableStateOf<String?>(null) }
    var esdeReload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(hasAccess, rescanCount) {
        if (hasAccess) {
            val (found, note) = withContext(Dispatchers.IO) {
                val roots = LogoIndex.searchRoots(Storage.volumeRoots(context))
                val files = LogoIndex.find(roots, catalog)
                val images = files.mapNotNull { (id, file) ->
                    LogoRenderer.render(file, 480, 200)?.let { id to it.asImageBitmap() }
                }.toMap()
                val text = when {
                    roots.isEmpty() -> "Logos: none. No theme or icons folder was found on this device."
                    files.isEmpty() -> "Logos: none found in " + roots.joinToString(", ") { it.path }
                    images.size < files.size -> "Logos: ${images.size} shown, ${files.size - images.size} could not be drawn."
                    else -> "Logos: ${images.size} found."
                }
                images to text
            }
            logos = found
            logoNote = note
            logosLoaded = true
        }
    }

    fun chosenEmulator(recipe: SystemRecipe): Emulator {
        val name = choices[recipe.id] ?: Prefs.loadEmulatorChoice(context, recipe.id)
        return (listOf(recipe.emulator) + recipe.alternates).firstOrNull { it.name == name } ?: recipe.emulator
    }

    fun toggle(id: String) {
        val current = selected ?: emptySet()
        val next = if (id in current) current - id else current + id
        selected = next
        Prefs.saveSelection(context, next)
    }

    BackHandler(enabled = screen != Screen.Detect) {
        val current = screen
        screen = when {
            current is Screen.Detail && current.fromReview -> Screen.Review
            current == Screen.Esde -> Screen.Review
            current is Screen.Detail || current == Screen.Review -> Screen.Systems
            else -> Screen.Detect
        }
    }

    val chosen = selected ?: emptySet()
    when (val current = screen) {
        Screen.Detect -> DetectScreen(
            device = device,
            volume = volume,
            hasAccess = hasAccess,
            scanning = scanning,
            onGrantAccess = {
                try {
                    context.startActivity(Storage.accessIntent(context))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Storage.fallbackAccessIntent())
                }
            },
            onRescan = { rescanCount += 1 },
            onContinue = {
                // Offer a theme only when the logo search has finished and found nothing.
                val offerTheme = hasAccess && logosLoaded && logos.isEmpty() &&
                    catalog.themes.isNotEmpty() && !themeSkipped
                screen = if (offerTheme) Screen.Theme else Screen.Systems
            },
        )

        Screen.Theme -> ThemeScreen(
            themes = catalog.themes,
            state = ThemeInstaller.state.value,
            onPick = { option ->
                ThemeInstaller.install(context, option) {
                    Prefs.saveTheme(context, option.repo.substringAfter('/'))
                    rescanCount += 1
                }
            },
            onContinue = {
                themeSkipped = true
                screen = Screen.Systems
            },
        )

        Screen.Systems -> SystemsScreen(
            catalog = catalog,
            scan = volume?.card,
            selected = chosen,
            logos = logos,
            logoNote = logoNote,
            onGetLogos = if (hasAccess && logosLoaded && logos.isEmpty() && catalog.themes.isNotEmpty()) {
                { screen = Screen.Theme }
            } else {
                null
            },
            onToggle = { toggle(it) },
            onDetails = { screen = Screen.Detail(it, fromReview = false) },
            onContinue = {
                Prefs.saveSelection(context, chosen)
                screen = Screen.Review
            },
        )

        is Screen.Detail -> {
            val recipe = catalog.byId(current.systemId)
            if (recipe == null) {
                screen = Screen.Systems
            } else {
                val emulator = chosenEmulator(recipe)
                LaunchedEffect(recipe.id, emulator.name, resumeCount) {
                    InstallManager.refresh(context, emulator)
                }
                DetailScreen(
                    recipe = recipe,
                    scan = volume?.card,
                    device = device,
                    on = recipe.id in chosen,
                    chosen = emulator,
                    onChoose = { picked ->
                        choices[recipe.id] = picked.name
                        Prefs.saveEmulatorChoice(context, recipe.id, picked.name)
                    },
                    installState = InstallManager.states[emulator.name] ?: InstallState.Idle,
                    notice = InstallManager.notices[emulator.name],
                    onInstall = { InstallManager.install(context, emulator) },
                    onAllowInstalls = {
                        try {
                            context.startActivity(InstallManager.permissionIntent(context))
                        } catch (_: ActivityNotFoundException) {
                            // No such settings page on this device. Android will ask during the install instead.
                        }
                    },
                    onCancelInstall = { InstallManager.cancel(context, emulator) },
                    onOpenPage = { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: ActivityNotFoundException) {
                            // No browser on this device.
                        }
                    },
                    onToggle = { toggle(recipe.id) },
                    onBack = { screen = if (current.fromReview) Screen.Review else Screen.Systems },
                )
            }
        }

        Screen.Review -> {
            LaunchedEffect(chosen, resumeCount) {
                catalog.systems.filter { it.id in chosen }.forEach { InstallManager.refresh(context, chosenEmulator(it)) }
            }
            ReviewScreen(
                catalog = catalog,
                volume = volume,
                selected = chosen,
                installStates = InstallManager.states,
                emulatorFor = { chosenEmulator(it) },
                onOpen = { screen = Screen.Detail(it, fromReview = true) },
                onFoldersCreated = { rescanCount += 1 },
                onEsde = {
                    esdeView = EsdeView.Loading
                    esdeMessage = null
                    screen = Screen.Esde
                },
                onBack = { screen = Screen.Systems },
            )
        }

        Screen.Esde -> {
            val picks = catalog.systems.filter { it.id in chosen }.map { recipe ->
                EsdePick(recipe, chosenEmulator(recipe), volume?.card?.folderFor(recipe.id)?.name)
            }
            LaunchedEffect(esdeReload, resumeCount) {
                esdeView = withContext(Dispatchers.IO) { EsdeHookup.load(context, picks) }
            }
            EsdeScreen(
                view = esdeView,
                message = esdeMessage,
                onApply = { rows ->
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { EsdePlanner.apply(rows) }
                        esdeMessage = if (failed.isEmpty()) {
                            "Done. ${rows.size} changed. Open ES-DE to check."
                        } else {
                            "${rows.size - failed.size} changed, ${failed.size} could not be written."
                        }
                        esdeReload += 1
                    }
                },
                onUseTheme = { folder ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { EsdeHookup.applyTheme(context, folder) }
                        esdeMessage = if (ok) {
                            "ES-DE will use the downloaded theme next time it opens."
                        } else {
                            "Loadout couldn't change the theme. Pick it in ES-DE under UI Settings."
                        }
                        esdeReload += 1
                    }
                },
                onBack = { screen = Screen.Review },
            )
        }
    }
}
