package app.loadout.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.loadout.data.Catalog
import app.loadout.device.DeviceDetector
import app.loadout.storage.Prefs
import app.loadout.storage.Storage
import app.loadout.storage.VolumeScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Detect : Screen
    data object Systems : Screen
    data class Detail(val systemId: String) : Screen
    data object Review : Screen
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

    fun toggle(id: String) {
        val current = selected ?: emptySet()
        val next = if (id in current) current - id else current + id
        selected = next
        Prefs.saveSelection(context, next)
    }

    BackHandler(enabled = screen != Screen.Detect) {
        screen = when (screen) {
            is Screen.Detail, Screen.Review -> Screen.Systems
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
            onContinue = { screen = Screen.Systems },
        )

        Screen.Systems -> SystemsScreen(
            catalog = catalog,
            scan = volume?.card,
            selected = chosen,
            onToggle = { toggle(it) },
            onDetails = { screen = Screen.Detail(it) },
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
                DetailScreen(
                    recipe = recipe,
                    scan = volume?.card,
                    device = device,
                    on = recipe.id in chosen,
                    onToggle = { toggle(recipe.id) },
                    onBack = { screen = Screen.Systems },
                )
            }
        }

        Screen.Review -> ReviewScreen(
            catalog = catalog,
            scan = volume?.card,
            selected = chosen,
            onBack = { screen = Screen.Systems },
        )
    }
}
