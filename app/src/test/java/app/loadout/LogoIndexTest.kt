package app.loadout

import app.loadout.data.Recipes
import app.loadout.storage.LogoIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogoIndexTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    private fun file(path: String): File {
        val target = File(temp.root, path)
        target.parentFile?.mkdirs()
        target.writeText("x")
        return target
    }

    @Test
    fun findsThemeLogosByFolderNameAndAlias() {
        file("ES-DE/themes/sample/_inc/systems/logos/nes.svg")
        file("ES-DE/themes/sample/_inc/systems/logos/megadrive.svg")
        file("ES-DE/themes/sample/_inc/systems/logos/unknown-system.svg")
        val found = LogoIndex.find(LogoIndex.searchRoots(listOf(temp.root)), catalog)
        assertEquals("nes.svg", found["nes"]?.name)
        assertEquals("megadrive.svg", found["genesis"]?.name)
        assertEquals(2, found.size)
    }

    @Test
    fun ignoresThemeImagesThatAreNotLogos() {
        file("ES-DE/themes/sample/_inc/systems/backgrounds/nes.png")
        val found = LogoIndex.find(LogoIndex.searchRoots(listOf(temp.root)), catalog)
        assertNull(found["nes"])
    }

    @Test
    fun userIconsWinOverThemeLogos() {
        file("ES-DE/themes/sample/logos/ps2.svg")
        val mine = file("Loadout/icons/ps2.png")
        val found = LogoIndex.find(LogoIndex.searchRoots(listOf(temp.root)), catalog)
        assertEquals(mine, found["ps2"])
    }

    @Test
    fun noFoldersMeansNoLogos() {
        assertEquals(emptyMap<String, File>(), LogoIndex.find(LogoIndex.searchRoots(listOf(temp.root)), catalog))
    }
}
