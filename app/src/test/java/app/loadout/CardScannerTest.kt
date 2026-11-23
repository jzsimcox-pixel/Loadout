package app.loadout

import app.loadout.data.Recipes
import app.loadout.storage.CardScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CardScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    private fun file(root: File, path: String) {
        val target = File(root, path)
        target.parentFile?.mkdirs()
        target.writeText("x")
    }

    @Test
    fun emptyCardHasNoRomsRoot() {
        val scan = CardScanner.scan(temp.root, catalog)
        assertNull(scan.romsRoot)
        assertEquals(0, scan.totalGames)
    }

    @Test
    fun countsGamesPerSystemInEsdeLayout() {
        val card = temp.root
        file(card, "ROMs/nes/Game One.nes")
        file(card, "ROMs/nes/Game Two.zip")
        file(card, "ROMs/nes/notes.txt")
        file(card, "ROMs/snes/Game.sfc")
        file(card, "ROMs/ps2/Game.iso")
        File(card, "ROMs/gba").mkdirs()

        val scan = CardScanner.scan(card, catalog)
        assertEquals("ROMs", scan.romsRoot?.name)
        assertEquals(2, scan.gamesFor("nes"))
        assertEquals(1, scan.gamesFor("snes"))
        assertEquals(1, scan.gamesFor("ps2"))
        assertEquals(0, scan.gamesFor("gba"))
        assertTrue(scan.folderFor("gba") != null)
        assertNull(scan.folderFor("n64"))
        assertEquals(4, scan.totalGames)
        assertEquals(3, scan.systemsWithGames)
    }

    @Test
    fun binTracksAreNotCountedWhenACueExists() {
        val card = temp.root
        file(card, "ROMs/psx/Game/Game.cue")
        file(card, "ROMs/psx/Game/Game (Track 1).bin")
        file(card, "ROMs/psx/Game/Game (Track 2).bin")
        file(card, "ROMs/psx/Other.chd")
        file(card, "ROMs/psx/Loose.bin")

        val scan = CardScanner.scan(card, catalog)
        assertEquals(3, scan.gamesFor("psx"))
    }

    @Test
    fun folderNamesMatchWithoutCaseAndByAlias() {
        val card = temp.root
        file(card, "roms/MegaDrive/Game.md")
        file(card, "roms/GameCube/Game.rvz")

        val scan = CardScanner.scan(card, catalog)
        assertEquals(1, scan.gamesFor("genesis"))
        assertEquals(1, scan.gamesFor("gc"))
    }

    @Test
    fun unknownFoldersAreReportedAndLeftAlone() {
        val card = temp.root
        file(card, "ROMs/nes/Game.nes")
        file(card, "ROMs/my stuff/thing.dat")

        val scan = CardScanner.scan(card, catalog)
        assertEquals(listOf("my stuff"), scan.unsorted)
        assertTrue(File(card, "ROMs/my stuff/thing.dat").exists())
    }

    @Test
    fun prefersTheRootWithRecognisedSystems() {
        val card = temp.root
        File(card, "Emulation/ROMs").mkdirs()
        file(card, "Emulation/ROMs/nes/Game.nes")
        file(card, "Emulation/ROMs/snes/Game.sfc")

        val scan = CardScanner.scan(card, catalog)
        assertEquals(2, scan.totalGames)
    }
}
