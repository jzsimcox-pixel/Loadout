package app.loadout

import app.loadout.data.Recipes
import app.loadout.data.Templates
import app.loadout.install.AppMatcher
import app.loadout.storage.SettingsPlanner
import app.loadout.storage.SettingsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Checks the real templates that ship inside the app. */
class TemplatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir = File("src/main/assets/templates")
    private val manifest = Templates.parse(File(dir, "templates.json").readText())
    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    @Test
    fun everyTemplateFileExistsAndUsesAKnownMode() {
        assertTrue(manifest.emulators.isNotEmpty())
        for ((name, emulator) in manifest.emulators) {
            assertTrue(name, emulator.files.isNotEmpty())
            for (file in emulator.files) {
                assertTrue(file.template, File(dir, file.template).isFile)
                assertTrue(file.template, file.mode in setOf("merge-cfg", "merge-ini", "merge-json", "replace"))
                assertFalse(file.path, file.path.startsWith("/") || ".." in file.path)
            }
        }
    }

    @Test
    fun everyTemplateBelongsToAnEmulatorInTheCatalog() {
        val known = catalog.systems.flatMap { listOf(it.emulator) + it.alternates }.map { AppMatcher.baseName(it.name) }.toSet()
        for (name in manifest.emulators.keys) assertTrue(name, name in known)
    }

    @Test
    fun templatesCarryNothingFromTheDeviceTheyWereCopiedFrom() {
        val cardId = Regex("[0-9A-F]{4}-[0-9A-F]{4}")
        val forbidden = listOf("content://", "token", "serial_", "title_id", "Analytics]\nID")
        for (file in dir.walkTopDown().filter { it.isFile }) {
            val text = file.readText()
            assertFalse("card id in ${file.name}", cardId.containsMatchIn(text))
            for (word in forbidden) assertFalse("$word in ${file.name}", word in text)
        }
    }

    @Test
    fun applyingEveryTemplateToAFreshDeviceThenPlanningAgainChangesNothing() {
        val internal = temp.root
        val values = mapOf(
            "INTERNAL" to internal.path,
            "PKG" to "pkg",
            "RETROARCH_DIR" to "${internal.path}/RetroArch",
            "RETROARCH_DATA" to "/data/user/0/pkg",
        )
        // Pretend each emulator has run once and created its folders.
        File(internal, "RetroArch").mkdirs()
        File(internal, "Armsx2").mkdirs()
        File(internal, "Android/data/pkg/files").mkdirs()

        for ((name, emulator) in manifest.emulators) {
            val read = { path: String -> File(dir, path).takeIf { it.isFile }?.readText() }
            val first = SettingsPlanner.plan(name, emulator, values, readTemplate = read)
            assertEquals(name, SettingsStatus.READY, first.status)
            assertEquals(name, 0, first.unreadable)
            assertTrue(name, SettingsPlanner.apply(listOf(first)).isEmpty())
            for (file in first.files) {
                assertFalse(file.path, "{{" in File(file.path).readText())
            }

            val second = SettingsPlanner.plan(name, emulator, values, readTemplate = read)
            assertEquals(name, SettingsStatus.MATCHES, second.status)
            assertTrue(name, second.files.isEmpty())
        }
    }
}
