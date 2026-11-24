package app.loadout

import app.loadout.data.EmulatorTemplates
import app.loadout.data.TemplateFile
import app.loadout.storage.FileDoor
import app.loadout.storage.SettingsPlanner
import app.loadout.storage.SettingsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsPlannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val templates = mapOf(
        "ra.patch" to "video_driver = \"vulkan\"\nsystem_directory = \"{{BIOS_DIR}}\"\n",
        "pad.ini" to "[Pad1]\nType = DualShock2\n",
        "ps2.json" to """{"global":{"renderer":"vulkan"}}""",
        "preset.slangp" to "#reference \"{{RETROARCH_DATA}}/x.slangp\"\n",
    )

    private fun values() = mapOf("INTERNAL" to temp.root.path, "PKG" to "com.example.emu")

    private fun plan(vararg files: TemplateFile, closedDoor: FileDoor? = null) =
        SettingsPlanner.plan("Test", EmulatorTemplates(files.toList()), values(), closedDoor) { templates[it] }

    /** Stands in for the wireless debugging connection: folders and files held in memory. */
    private class FakeDoor(val dirs: Set<String>, val files: MutableMap<String, String>) : FileDoor {
        override val createsFiles = false
        val backups = ArrayList<String>()
        override fun isDir(path: String) = path in dirs
        override fun read(path: String) = files[path]
        override fun write(path: String, text: String) {
            backups += path
            files[path] = text
        }
    }

    private fun closedRoot() = "${temp.root.path}/Android/data/com.example.emu/files"

    private fun file(template: String, root: String, path: String, mode: String, optional: String = "") =
        TemplateFile(template, root, path, mode, optional)

    @Test
    fun aFolderThatDoesNotExistMeansNothingIsPlanned() {
        val row = plan(file("ra.patch", "{{INTERNAL}}/RetroArch", "retroarch.cfg", "merge-cfg"))
        assertEquals(SettingsStatus.NO_FOLDER, row.status)
        assertTrue(row.files.isEmpty())
    }

    @Test
    fun aClosedAndroidDataFolderIsReportedAsLocked() {
        val row = plan(file("ra.patch", "{{INTERNAL}}/Android/data/{{PKG}}/files", "retroarch.cfg", "merge-cfg"))
        assertEquals(SettingsStatus.LOCKED, row.status)
        assertEquals(1, row.lockedFiles)
    }

    @Test
    fun changesArePlannedButNothingIsWrittenUntilApply() {
        val root = temp.newFolder("RetroArch")
        val cfg = File(root, "retroarch.cfg").apply { writeText("video_driver = \"gl\"\nmine = \"1\"\n") }
        val row = plan(file("ra.patch", "{{INTERNAL}}/RetroArch", "retroarch.cfg", "merge-cfg"))

        assertEquals(SettingsStatus.READY, row.status)
        // The BIOS folder is unknown, so that line is dropped instead of written as a placeholder.
        assertEquals(listOf("video_driver"), row.changes.map { it.key })
        assertEquals("video_driver = \"gl\"\nmine = \"1\"\n", cfg.readText())

        assertTrue(SettingsPlanner.apply(listOf(row)).isEmpty())
        assertEquals("video_driver = \"vulkan\"\nmine = \"1\"\n", cfg.readText())
        assertEquals("video_driver = \"gl\"\nmine = \"1\"\n", File(root, "retroarch.cfg.loadout-backup").readText())
    }

    @Test
    fun planningAgainAfterApplyFindsNothingToDo() {
        val root = temp.newFolder("Armsx2")
        File(root, "pad.ini").writeText("[Pad1]\nType = None\n")
        val files = arrayOf(
            file("pad.ini", "{{INTERNAL}}/Armsx2", "pad.ini", "merge-ini"),
            file("ps2.json", "{{INTERNAL}}/Armsx2", "settings.json", "merge-json"),
        )
        val first = plan(*files)
        assertEquals(2, first.files.size)
        assertTrue(SettingsPlanner.apply(listOf(first)).isEmpty())

        val second = plan(*files)
        assertEquals(SettingsStatus.MATCHES, second.status)
        assertTrue(second.files.isEmpty())
    }

    @Test
    fun aFileChangedSincePlanningIsNotOverwritten() {
        val root = temp.newFolder("RetroArch")
        val cfg = File(root, "retroarch.cfg").apply { writeText("video_driver = \"gl\"\n") }
        val row = plan(file("ra.patch", "{{INTERNAL}}/RetroArch", "retroarch.cfg", "merge-cfg"))
        cfg.writeText("video_driver = \"gl\"\nadded_by_the_emulator = \"1\"\n")

        assertEquals(listOf(cfg.path), SettingsPlanner.apply(listOf(row)))
        assertEquals("video_driver = \"gl\"\nadded_by_the_emulator = \"1\"\n", cfg.readText())
        assertFalse(File(root, "retroarch.cfg.loadout-backup").exists())
    }

    @Test
    fun aSettingsFileThatCannotBeParsedIsLeftAlone() {
        val root = temp.newFolder("Armsx2")
        val json = File(root, "settings.json").apply { writeText("{ broken") }
        val row = plan(file("ps2.json", "{{INTERNAL}}/Armsx2", "settings.json", "merge-json"))
        assertEquals(1, row.unreadable)
        assertTrue(row.files.isEmpty())
        assertEquals("{ broken", json.readText())
    }

    @Test
    fun optionalExtrasAndUnfilledWholeFilesAreSkipped() {
        temp.newFolder("RetroArch")
        val optional = plan(file("ra.patch", "{{INTERNAL}}/RetroArch", "retroarch.cfg", "merge-cfg", optional = "shader"))
        assertTrue(optional.files.isEmpty())
        val unfilled = plan(file("preset.slangp", "{{INTERNAL}}/RetroArch", "global.slangp", "replace"))
        assertTrue(unfilled.files.isEmpty())
        assertFalse(File(temp.root, "RetroArch/global.slangp").exists())
    }

    @Test
    fun missingSubfoldersAreCreatedOnApply() {
        temp.newFolder("RetroArch")
        val row = plan(file("ra.patch", "{{INTERNAL}}/RetroArch", "config/Core/Core.opt", "merge-cfg"))
        assertEquals(SettingsStatus.READY, row.status)
        assertTrue(SettingsPlanner.apply(listOf(row)).isEmpty())
        assertEquals("video_driver = \"vulkan\"\n", File(temp.root, "RetroArch/config/Core/Core.opt").readText())
    }

    @Test
    fun aClosedFolderIsReadAndWrittenThroughTheConnection() {
        val path = "${closedRoot()}/retroarch.cfg"
        val door = FakeDoor(setOf(closedRoot()), mutableMapOf(path to "video_driver = \"gl\"\n"))
        val row = plan(
            file("ra.patch", "{{INTERNAL}}/Android/data/{{PKG}}/files", "retroarch.cfg", "merge-cfg"),
            closedDoor = door,
        )
        assertEquals(SettingsStatus.READY, row.status)
        assertEquals(0, row.lockedFiles)
        assertEquals("video_driver = \"gl\"\n", door.files[path])

        assertTrue(SettingsPlanner.apply(listOf(row)).isEmpty())
        assertEquals("video_driver = \"vulkan\"\n", door.files[path])
        assertEquals(listOf(path), door.backups)
    }

    @Test
    fun throughTheConnectionOnlyFilesThatExistAreChanged() {
        val door = FakeDoor(setOf(closedRoot()), mutableMapOf())
        val row = plan(
            file("ra.patch", "{{INTERNAL}}/Android/data/{{PKG}}/files", "retroarch.cfg", "merge-cfg"),
            closedDoor = door,
        )
        assertEquals(SettingsStatus.NO_FOLDER, row.status)
        assertEquals(1, row.notCreated)
        assertTrue(row.files.isEmpty())
        assertTrue(door.files.isEmpty())
    }

    @Test
    fun aClosedFolderTheEmulatorHasNotMadeIsNotLocked() {
        val row = plan(
            file("ra.patch", "{{INTERNAL}}/Android/data/{{PKG}}/files", "retroarch.cfg", "merge-cfg"),
            closedDoor = FakeDoor(emptySet(), mutableMapOf()),
        )
        assertEquals(SettingsStatus.NO_FOLDER, row.status)
        assertEquals(0, row.lockedFiles)
    }

    @Test
    fun aConnectionThatFailsLeavesTheFileAlone() {
        val broken = object : FileDoor {
            override val createsFiles = false
            override fun isDir(path: String) = true
            override fun read(path: String): String? = throw java.io.IOException("cut short")
            override fun write(path: String, text: String) = throw java.io.IOException("should not be called")
        }
        val row = plan(
            file("ra.patch", "{{INTERNAL}}/Android/data/{{PKG}}/files", "retroarch.cfg", "merge-cfg"),
            closedDoor = broken,
        )
        assertEquals(1, row.unreadable)
        assertTrue(row.files.isEmpty())
    }
}
