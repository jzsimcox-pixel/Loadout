package app.loadout.storage

import app.loadout.data.EmulatorTemplates
import java.io.File

enum class SettingsStatus {
    /** At least one file would change. */
    READY,

    /** Every file Loadout can reach already has these settings. */
    MATCHES,

    /** Android keeps the emulator's settings folder closed to other apps. */
    LOCKED,

    /** The emulator has not created its settings folder yet. */
    NO_FOLDER,
    NOT_INSTALLED,
}

/**
 * A way of reaching files. Loadout reads and writes most folders itself; the folders Android
 * keeps closed are reached through the wireless debugging connection instead.
 */
interface FileDoor {
    /** False when this door only changes files that already exist. */
    val createsFiles: Boolean

    fun isDir(path: String): Boolean

    /** The file's text, or null when it does not exist. Throws when it cannot be read. */
    fun read(path: String): String?

    /** Backs the file up on its first edit, then writes it. Throws when that fails. */
    fun write(path: String, text: String)
}

/** Plain file access, for everything outside Android's closed folders. */
object DirectDoor : FileDoor {
    override val createsFiles = true

    override fun isDir(path: String): Boolean = File(path).let { it.isDirectory && it.list() != null }

    override fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    override fun write(path: String, text: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        if (target.isFile) EsdePlanner.backup(target)
        target.writeText(text)
    }
}

/**
 * A settings file with its merged text, ready to write. original is what was read to build it,
 * or null when the file did not exist.
 */
class PlannedFile(
    val path: String,
    val original: String?,
    val text: String,
    val changes: List<SettingChange>,
    val door: FileDoor,
)

/**
 * files: only the ones that would change. lockedFiles: files in a folder Android keeps closed,
 * with no connection to reach them. unreadable: files Loadout could not make sense of and so
 * will not touch. notCreated: files the emulator has not made yet, in a folder where Loadout
 * only changes files that exist.
 */
data class SettingsRow(
    val emulator: String,
    val status: SettingsStatus,
    val files: List<PlannedFile> = emptyList(),
    val lockedFiles: Int = 0,
    val unreadable: Int = 0,
    val notCreated: Int = 0,
) {
    /** Changes worth showing. Eden pairs every setting with a "default" flag, which is noise. */
    val changes: List<SettingChange>
        get() {
            val all = files.flatMap { it.changes }
            return all.filterNot { it.key.endsWith("\\default") }.ifEmpty { all }
        }
}

/** Works out what the settings templates would change, and writes them. No Android code here. */
object SettingsPlanner {
    private val protectedFolder = Regex("/Android/(data|obb)/")

    private fun merge(mode: String, existing: String, template: String, values: Map<String, String>): MergeResult? =
        when (mode) {
            "merge-cfg" -> CfgMerge.merge(existing, Placeholders.fillLines(template, values))
            "merge-ini" -> IniMerge.merge(existing, Placeholders.fillLines(template, values))
            "merge-json" -> JsonMerge.merge(existing, template)
            "replace" -> {
                val filled = Placeholders.fill(template, values)
                when {
                    Placeholders.unresolved(filled) -> null
                    filled == existing -> MergeResult(existing, emptyList())
                    else -> MergeResult(
                        filled,
                        listOf(SettingChange("whole file", "current file".takeIf { existing.isNotEmpty() }, "Loadout's version")),
                    )
                }
            }
            else -> null
        }

    /**
     * Compares one emulator's templates with the files on the device. Nothing is written.
     * values fills the {{NAME}} placeholders. readTemplate returns a template's text, or null.
     * closedDoor reaches the folders Android keeps closed, or is null when there is no
     * connection, in which case those files are counted as locked.
     */
    fun plan(
        emulator: String,
        templates: EmulatorTemplates,
        values: Map<String, String>,
        closedDoor: FileDoor? = null,
        readTemplate: (String) -> String?,
    ): SettingsRow {
        val planned = ArrayList<PlannedFile>()
        var locked = 0
        var unreadable = 0
        var compared = 0
        var notCreated = 0
        for (file in templates.files) {
            // Extras such as a shader are only written once the user has asked for them.
            if (file.optional.isNotEmpty()) continue
            val rootPath = Placeholders.fill(file.root, values)
            if (Placeholders.unresolved(rootPath)) continue
            val door: FileDoor = try {
                when {
                    DirectDoor.isDir(rootPath) -> DirectDoor
                    !protectedFolder.containsMatchIn(rootPath) -> continue
                    closedDoor == null -> {
                        locked += 1
                        continue
                    }
                    closedDoor.isDir(rootPath) -> closedDoor
                    else -> continue
                }
            } catch (_: Exception) {
                unreadable += 1
                continue
            }
            val template = readTemplate(file.template)
            if (template == null) {
                unreadable += 1
                continue
            }
            val path = File(rootPath, file.path).path
            try {
                val existing = door.read(path)
                if (existing == null && !door.createsFiles) {
                    notCreated += 1
                    continue
                }
                val result = merge(file.mode, existing.orEmpty(), template, values)
                // null: the template needs a value Loadout does not have yet, so it is skipped.
                if (result != null) {
                    compared += 1
                    if (result.changes.isNotEmpty()) planned += PlannedFile(path, existing, result.text, result.changes, door)
                }
            } catch (_: Exception) {
                // A file Loadout cannot read or parse is left alone.
                unreadable += 1
            }
        }
        val status = when {
            planned.isNotEmpty() -> SettingsStatus.READY
            compared > 0 -> SettingsStatus.MATCHES
            locked > 0 -> SettingsStatus.LOCKED
            else -> SettingsStatus.NO_FOLDER
        }
        return SettingsRow(emulator, status, planned, locked, unreadable, notCreated)
    }

    /**
     * Writes the planned files, keeping a backup of each one's first original beside it.
     * A file that changed since it was compared is skipped. Returns the paths not written.
     */
    fun apply(rows: List<SettingsRow>): List<String> {
        val failed = ArrayList<String>()
        for (file in rows.flatMap { it.files }) {
            try {
                if (file.door.read(file.path) != file.original) {
                    failed += file.path
                    continue
                }
                file.door.write(file.path, file.text)
            } catch (_: Exception) {
                failed += file.path
            }
        }
        return failed
    }
}
