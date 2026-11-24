package app.loadout.install

import android.content.Context
import app.loadout.storage.EsdeCommand
import app.loadout.storage.EsdePick
import app.loadout.storage.EsdePlanner
import app.loadout.storage.EsdeRow
import app.loadout.storage.EsdeSettings
import app.loadout.storage.EsdeSystems
import app.loadout.storage.LogoIndex
import app.loadout.storage.Prefs
import app.loadout.storage.Storage
import java.io.File
import java.util.zip.ZipFile

sealed interface EsdeView {
    data object Loading : EsdeView
    data object NotInstalled : EsdeView
    data object NoDataFolder : EsdeView
    data object NoSystemList : EsdeView

    /** themeOffer: a theme Loadout installed that ES-DE is not using, or null. */
    data class Ready(
        val rows: List<EsdeRow>,
        val currentTheme: String?,
        val themeOffer: String?,
    ) : EsdeView
}

/** Connects Loadout's emulator choices to ES-DE. Everything here runs off the main thread. */
object EsdeHookup {
    private val knownPackages = listOf("org.es_de.frontend")

    private fun apkPaths(context: Context): List<String>? {
        val manager = context.packageManager
        val app = knownPackages.firstNotNullOfOrNull { name ->
            try {
                manager.getApplicationInfo(name, 0)
            } catch (_: Exception) {
                null
            }
        } ?: manager.getInstalledApplications(0).firstOrNull {
            manager.getApplicationLabel(it).toString().lowercase().filter(Char::isLetterOrDigit) == "esde"
        } ?: return null
        return listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
    }

    /** ES-DE ships its emulator list inside its own install package. Read it from there. */
    private fun bundledSystems(apks: List<String>): Map<String, List<EsdeCommand>> {
        for (path in apks) {
            try {
                ZipFile(path).use { zip ->
                    val entries = zip.entries().toList().filter { it.name.endsWith("es_systems.xml") }
                    val entry = entries.firstOrNull { "android" in it.name.lowercase() } ?: entries.firstOrNull()
                    if (entry != null) {
                        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        val parsed = EsdeSystems.parse(text)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
                // Try the next package file.
            }
        }
        return emptyMap()
    }

    private fun dataDir(context: Context): File? {
        val candidates = LogoIndex.esdeDirs(Storage.volumeRoots(context))
        return candidates.firstOrNull { File(it, "settings/es_settings.xml").isFile }
            ?: candidates.firstOrNull { File(it, "gamelists").isDirectory }
    }

    fun load(context: Context, picks: List<EsdePick>): EsdeView {
        val apks = apkPaths(context) ?: return EsdeView.NotInstalled
        val data = dataDir(context) ?: return EsdeView.NoDataFolder
        val systems = LinkedHashMap(bundledSystems(apks))
        val custom = File(data, "custom_systems/es_systems.xml")
        if (custom.isFile) {
            try {
                systems.putAll(EsdeSystems.parse(custom.readText()))
            } catch (_: Exception) {
                // A broken custom file is ES-DE's to report. Use the bundled list.
            }
        }
        if (systems.isEmpty()) return EsdeView.NoSystemList

        val settings = File(data, "settings/es_settings.xml")
        val currentTheme = if (settings.isFile) EsdeSettings.currentTheme(settings.readText()) else null
        val wanted = Prefs.loadTheme(context)
        val offer = wanted?.takeIf {
            currentTheme != null && it != currentTheme && File(data, "themes/$it").isDirectory
        }
        return EsdeView.Ready(EsdePlanner.plan(picks, systems, data), currentTheme, offer)
    }

    /** Switches ES-DE to the given theme folder. Returns false when nothing could be changed. */
    fun applyTheme(context: Context, folderName: String): Boolean {
        val settings = dataDir(context)?.let { File(it, "settings/es_settings.xml") } ?: return false
        if (!settings.isFile) return false
        return try {
            val updated = EsdeSettings.withTheme(settings.readText(), folderName) ?: return false
            EsdePlanner.backup(settings)
            settings.writeText(updated)
            true
        } catch (_: Exception) {
            false
        }
    }
}
