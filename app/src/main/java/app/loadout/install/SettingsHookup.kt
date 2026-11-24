package app.loadout.install

import android.content.Context
import android.os.Environment
import app.loadout.data.Catalog
import app.loadout.data.Templates
import app.loadout.debug.DebugLink
import app.loadout.debug.LinkResult
import app.loadout.device.DeviceInfo
import app.loadout.storage.SettingsPlanner
import app.loadout.storage.SettingsRow
import app.loadout.storage.SettingsStatus
import java.io.File

sealed interface SettingsView {
    data object Loading : SettingsView
    data object NoTemplates : SettingsView

    /**
     * tunedOn: the device the settings came from, when it is not this one.
     * linked: wireless debugging is connected, so closed folders can be reached.
     */
    data class Ready(val rows: List<SettingsRow>, val tunedOn: String?, val linked: Boolean) : SettingsView
}

/** Compares Loadout's tuned settings with each installed emulator. Runs off the main thread. */
object SettingsHookup {
    private const val FOLDER = "templates"

    private fun asset(context: Context, path: String): String? =
        try {
            context.assets.open("$FOLDER/$path").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }

    fun load(context: Context, catalog: Catalog, device: DeviceInfo): SettingsView {
        val manifest = try {
            Templates.parse(asset(context, "templates.json") ?: return SettingsView.NoTemplates)
        } catch (_: Exception) {
            return SettingsView.NoTemplates
        }
        val internal = Environment.getExternalStorageDirectory().path
        val closedDoor = if (reconnect(context)) DebugLink.door(context) else null
        val emulators = catalog.systems.flatMap { listOf(it.emulator) + it.alternates }

        val rows = manifest.emulators.map { (name, templates) ->
            // "RetroArch (SwanStation)" and plain "RetroArch" are the same installed app.
            val candidates = emulators.filter { AppMatcher.baseName(it.name) == name }.sortedBy { it.name != name }
            val installed = candidates.firstNotNullOfOrNull { InstallManager.findInstalled(context, it) }
            if (installed == null) {
                SettingsRow(name, SettingsStatus.NOT_INSTALLED)
            } else {
                val values = mapOf(
                    "INTERNAL" to internal,
                    "PKG" to installed.packageId,
                    "RETROARCH_DIR" to File(internal, "RetroArch").path,
                    "RETROARCH_DATA" to "/data/user/0/${installed.packageId}",
                )
                SettingsPlanner.plan(name, templates, values, closedDoor) { asset(context, it) }
            }
        }
        val tunedOn = manifest.profileName.takeIf { it.isNotBlank() && manifest.profile != device.profileId }
        return SettingsView.Ready(rows, tunedOn, closedDoor != null)
    }

    /**
     * True when wireless debugging is connected. A device that was paired before is
     * reconnected quietly; one that never was is left for the setup guide.
     */
    private fun reconnect(context: Context): Boolean {
        if (DebugLink.isConnected(context)) return true
        if (!DebugLink.wasPaired(context) || !DebugLink.onWifi(context)) return false
        val reachable = DebugLink.wirelessDebuggingOn(context) != false || DebugLink.canToggleWireless(context)
        return reachable && DebugLink.connect(context, 6000) == LinkResult.Ready
    }

    /** Writes the chosen rows. Returns the paths that could not be written. */
    fun apply(rows: List<SettingsRow>): List<String> = SettingsPlanner.apply(rows)
}
