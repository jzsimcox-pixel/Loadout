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

/** A settings file with its merged text, ready to write. original is what was read to build it. */
class PlannedFile(
    val target: File,
    val original: String,
    val text: String,
    val changes: List<SettingChange>,
)

/**
 * files: only the ones that would change. lockedFiles: files in a folder Android keeps closed.
 * unreadable: files Loadout could not make sense of and so will not touch.
 */
data class SettingsRow(
    val emulator: String,
    val status: SettingsStatus,
    val files: List<PlannedFile> = emptyList(),
    val lockedFiles: Int = 0,
    val unreadable: Int = 0,
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

    private fun reachable(root: File): Boolean = root.isDirectory && root.list() != null

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
     */
    fun plan(
        emulator: String,
        templates: EmulatorTemplates,
        values: Map<String, String>,
        readTemplate: (String) -> String?,
    ): SettingsRow {
        val planned = ArrayList<PlannedFile>()
        var locked = 0
        var unreadable = 0
        var compared = 0
        for (file in templates.files) {
            // Extras such as a shader are only written once the user has asked for them.
            if (file.optional.isNotEmpty()) continue
            val rootPath = Placeholders.fill(file.root, values)
            if (Placeholders.unresolved(rootPath)) continue
            val root = File(rootPath)
            if (!reachable(root)) {
                if (protectedFolder.containsMatchIn(rootPath)) locked += 1
                continue
            }
            val template = readTemplate(file.template)
            if (template == null) {
                unreadable += 1
                continue
            }
            val target = File(root, file.path)
            try {
                val existing = if (target.isFile) target.readText() else ""
                val result = merge(file.mode, existing, template, values)
                // null: the template needs a value Loadout does not have yet, so it is skipped.
                if (result != null) {
                    compared += 1
                    if (result.changes.isNotEmpty()) planned += PlannedFile(target, existing, result.text, result.changes)
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
        return SettingsRow(emulator, status, planned, locked, unreadable)
    }

    /**
     * Writes the planned files, keeping a backup of each one's first original beside it.
     * A file that changed since it was compared is skipped. Returns the files not written.
     */
    fun apply(rows: List<SettingsRow>): List<File> {
        val failed = ArrayList<File>()
        for (file in rows.flatMap { it.files }) {
            try {
                val target = file.target
                val now = if (target.isFile) target.readText() else ""
                if (now != file.original) {
                    failed += target
                    continue
                }
                target.parentFile?.mkdirs()
                if (target.isFile) EsdePlanner.backup(target)
                target.writeText(file.text)
            } catch (_: Exception) {
                failed += file.target
            }
        }
        return failed
    }
}
