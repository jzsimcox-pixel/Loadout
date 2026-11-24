package app.loadout

import app.loadout.data.Recipes
import app.loadout.storage.LogoIndex
import app.loadout.storage.ThemeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val zip = temp.newFile("theme.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun unzipDropsTheTopFolderAndTheLogosAreThenFound() {
        val volume = temp.newFolder("volume")
        val zip = zipOf(
            "owner-theme-abc123/theme.xml" to "<theme/>",
            "owner-theme-abc123/_inc/systems/logos/nes.svg" to "<svg/>",
        )
        val target = File(volume, "ES-DE/themes/sample-es-de")
        ThemeFiles.unzip(zip, target)
        assertTrue(File(target, "theme.xml").isFile)
        assertTrue(File(target, "_inc/systems/logos/nes.svg").isFile)
        val found = LogoIndex.find(LogoIndex.searchRoots(listOf(volume)), catalog)
        assertEquals("nes.svg", found["nes"]?.name)
    }

    @Test
    fun unzipRefusesPathsThatEscapeTheTarget() {
        val volume = temp.newFolder("volume")
        val zip = zipOf("top/../../escaped.txt" to "x")
        try {
            ThemeFiles.unzip(zip, File(volume, "themes/sample"))
            fail("expected the archive to be refused")
        } catch (_: IOException) {
        }
        assertFalse(File(volume, "escaped.txt").exists())
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    @Test
    fun newDeviceGetsAnEsdeThemesFolderOnTheFirstVolume() {
        val internal = temp.newFolder("internal")
        val card = temp.newFolder("card")
        assertEquals(File(internal, "ES-DE/themes"), ThemeFiles.themesDir(listOf(internal, card)))
    }

    @Test
    fun existingEsdeFolderInUseIsPreferred() {
        val internal = temp.newFolder("internal")
        val card = temp.newFolder("card")
        File(internal, "ES-DE").mkdirs()
        File(card, "ES-DE/settings").mkdirs()
        File(card, "ES-DE/settings/es_settings.xml").writeText("<settings/>")
        assertEquals(File(card, "ES-DE/themes"), ThemeFiles.themesDir(listOf(internal, card)))
    }

    @Test
    fun logosOutsideALogoFolderAreFoundWhenTheyAreVectorImages() {
        val volume = temp.newFolder("volume")
        val file = File(volume, "ES-DE/themes/sample/_inc/systems/art/psx.svg")
        file.parentFile?.mkdirs()
        file.writeText("<svg/>")
        val found = LogoIndex.find(LogoIndex.searchRoots(listOf(volume)), catalog)
        assertEquals("psx.svg", found["psx"]?.name)
    }
}
