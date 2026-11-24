package app.loadout.install

import android.content.Context
import android.os.Environment
import app.loadout.data.Catalog
import app.loadout.data.Templates
import app.loadout.device.DeviceInfo
import app.loadout.storage.SettingsPlanner
import app.loadout.storage.SettingsRow
import app.loadout.storage.SettingsStatus
import java.io.File

sealed interface SettingsView {
    data object Loading : SettingsView
    data object NoTemplates : SettingsView

    /** tunedOn: the device the settings came from, when it is not this one. */
    data class Ready(val rows: List<SettingsRow>, val tunedOn: String?) : SettingsView
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
                SettingsPlanner.plan(name, templates, values) { asset(context, it) }
            }
        }
        val tunedOn = manifest.profileName.takeIf { it.isNotBlank() && manifest.profile != device.profileId }
        return SettingsView.Ready(rows, tunedOn)
    }

    /** Writes the chosen rows. Returns the files that could not be written. */
    fun apply(rows: List<SettingsRow>): List<File> = SettingsPlanner.apply(rows)
}
