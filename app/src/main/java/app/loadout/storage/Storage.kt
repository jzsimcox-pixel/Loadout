package app.loadout.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import app.loadout.data.Catalog
import java.io.File

data class VolumeScan(
    val root: File,
    val label: String,
    val removable: Boolean,
    val sizeBytes: Long,
    val card: CardScan,
)

object Storage {
    /** True when Loadout may read the whole card. */
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** Opens the system page where the user grants file access to Loadout. */
    fun accessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun fallbackAccessIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /**
     * Scans every mounted volume and returns the best one: a volume with an
     * ES-DE ROMs folder wins, then removable cards, then internal storage.
     */
    fun scan(context: Context, catalog: Catalog): VolumeScan {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes = manager?.storageVolumes.orEmpty()
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume ->
                val dir: File = volume.directory ?: return@mapNotNull null
                VolumeScan(
                    root = dir,
                    label = if (volume.isRemovable) "SD card" else "Internal storage",
                    removable = volume.isRemovable,
                    sizeBytes = dir.totalSpace,
                    card = CardScanner.scan(dir, catalog),
                )
            }
        if (volumes.isEmpty()) {
            val dir = Environment.getExternalStorageDirectory()
            return VolumeScan(dir, "Internal storage", false, dir.totalSpace, CardScanner.scan(dir, catalog))
        }
        return volumes.sortedWith(
            compareByDescending<VolumeScan> { it.card.romsRoot != null }
                .thenByDescending { it.card.totalGames }
                .thenByDescending { it.removable },
        ).first()
    }

    /** Every mounted storage volume: internal storage and any SD card. */
    fun volumeRoots(context: Context): List<File> {
        val manager = context.getSystemService(StorageManager::class.java)
        val roots = manager?.storageVolumes.orEmpty()
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { it.directory }
        return roots.ifEmpty { listOf(Environment.getExternalStorageDirectory()) }
    }

    fun formatSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 100) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
    }
}

object Prefs {
    private const val FILE = "loadout"
    private const val KEY_SELECTED = "selected_systems"

    /** Returns null when the user has never chosen systems. */
    fun loadSelection(context: Context): Set<String>? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED, null)
            ?.toSet()

    /** Remembers which Android package an emulator turned out to be, once Loadout has seen its APK. */
    fun loadPackageId(context: Context, emulatorName: String): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("pkg:$emulatorName", null)

    fun savePackageId(context: Context, emulatorName: String, packageId: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("pkg:$emulatorName", packageId)
            .apply()
    }

    /** Folder name of the ES-DE theme the user installed through Loadout, if any. */
    fun loadTheme(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("theme", null)

    fun saveTheme(context: Context, folderName: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("theme", folderName).apply()
    }

    /** Which emulator the user picked for a system. Null means the recommended one. */
    fun loadEmulatorChoice(context: Context, systemId: String): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("emu:$systemId", null)

    fun saveEmulatorChoice(context: Context, systemId: String, emulatorName: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("emu:$systemId", emulatorName)
            .apply()
    }

    fun saveSelection(context: Context, selected: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED, selected.toSet())
            .apply()
    }
}
