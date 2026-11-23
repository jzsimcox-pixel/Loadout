package app.loadout.storage

import app.loadout.data.Catalog
import app.loadout.data.SystemRecipe
import java.io.File

data class SystemScan(
    val systemId: String,
    val folder: File?,
    val games: Int,
)

data class CardScan(
    /** The ROMs folder that was found, or null when the card has none. */
    val romsRoot: File?,
    val systems: Map<String, SystemScan>,
    /** Folders inside the ROMs folder that match no known system. */
    val unsorted: List<String>,
) {
    val totalGames: Int get() = systems.values.sumOf { it.games }
    val systemsWithGames: Int get() = systems.values.count { it.games > 0 }

    fun gamesFor(systemId: String): Int = systems[systemId]?.games ?: 0
    fun folderFor(systemId: String): File? = systems[systemId]?.folder
}

/**
 * Reads a storage volume and reports what is on it. Read-only: nothing here
 * creates, moves, renames or deletes a file.
 */
object CardScanner {
    /** Places an ES-DE style ROMs folder is commonly kept, relative to the volume root. */
    val ROOT_CANDIDATES = listOf(
        "ROMs", "roms", "Roms",
        "ES-DE/ROMs", "Emulation/ROMs", "Emulation/roms",
    )

    /** When a folder holds one of these, its .bin files are tracks, not separate games. */
    private val INDEX_EXTENSIONS = setOf("cue", "gdi", "m3u")
    private const val MAX_DEPTH = 3

    fun scan(volume: File, catalog: Catalog): CardScan {
        val root = findRomsRoot(volume, catalog)
            ?: return CardScan(null, emptyMap(), emptyList())

        val children = root.listFiles().orEmpty().filter { it.isDirectory }
        val matched = HashSet<File>()
        val systems = HashMap<String, SystemScan>()
        for (recipe in catalog.systems) {
            val folder = folderFor(children, recipe)
            if (folder != null) {
                matched += folder
                val games = if (recipe.folderGames) {
                    countFolderGames(folder, recipe.extensions)
                } else {
                    countGames(folder, recipe.extensions)
                }
                systems[recipe.id] = SystemScan(recipe.id, folder, games)
            }
        }
        val unsorted = children.filter { it !in matched }.map { it.name }.sorted()
        return CardScan(root, systems, unsorted)
    }

    fun findRomsRoot(volume: File, catalog: Catalog): File? {
        val existing = ROOT_CANDIDATES
            .map { File(volume, it) }
            .filter { it.isDirectory }
            .distinctBy { it.canonicalPath }
        if (existing.isEmpty()) return null
        // Prefer the candidate that holds the most recognised system folders.
        return existing.maxByOrNull { candidate ->
            val dirs = candidate.listFiles().orEmpty().filter { it.isDirectory }
            catalog.systems.count { folderFor(dirs, it) != null }
        }
    }

    private fun folderFor(children: List<File>, recipe: SystemRecipe): File? {
        for (name in recipe.folders) {
            val hit = children.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * For systems that keep each game in its own folder: every folder directly
     * inside the system folder is one game, and so is every matching loose file.
     */
    fun countFolderGames(dir: File, extensions: List<String>): Int {
        val wanted = extensions.map { it.lowercase() }.toSet()
        return dir.listFiles().orEmpty().count { entry ->
            !entry.name.startsWith(".") &&
                (entry.isDirectory || (entry.isFile && entry.extension.lowercase() in wanted))
        }
    }

    fun countGames(dir: File, extensions: List<String>, depth: Int = 0): Int {
        val wanted = extensions.map { it.lowercase() }.toSet()
        val entries = dir.listFiles().orEmpty()
        val files = entries.filter { it.isFile && !it.name.startsWith(".") }
        val hasIndex = files.any { it.extension.lowercase() in INDEX_EXTENSIONS }
        var count = files.count { file ->
            val ext = file.extension.lowercase()
            ext in wanted && !(hasIndex && ext == "bin")
        }
        if (depth < MAX_DEPTH) {
            for (sub in entries) {
                if (sub.isDirectory && !sub.name.startsWith(".")) {
                    count += countGames(sub, extensions, depth + 1)
                }
            }
        }
        return count
    }
}

/** Works out which system folders are missing, and creates them when asked. */
object FolderPlanner {
    /** Folders that selected systems need but the card does not have yet. */
    fun plan(volumeRoot: File, scan: CardScan, catalog: Catalog, selected: Set<String>): List<File> {
        val romsRoot = scan.romsRoot ?: File(volumeRoot, "ROMs")
        return catalog.systems
            .filter { it.id in selected && scan.folderFor(it.id) == null }
            .map { File(romsRoot, it.folders.first()) }
    }

    /** Creates the folders. Never touches anything that already exists. Returns the ones that failed. */
    fun create(folders: List<File>): List<File> =
        folders.filter { folder -> !(folder.isDirectory || folder.mkdirs()) }
}
