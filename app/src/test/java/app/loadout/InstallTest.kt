package app.loadout

import app.loadout.data.AssetRule
import app.loadout.data.Recipes
import app.loadout.device.Chipsets
import app.loadout.install.GitHubReleases
import app.loadout.install.ReleaseAsset
import app.loadout.storage.CardScanner
import app.loadout.storage.FolderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    private fun assets(vararg names: String) = names.map { ReleaseAsset(it, "https://example.invalid/$it") }

    @Test
    fun parsesAReleaseAndIgnoresUnknownFields() {
        val release = GitHubReleases.parse(
            """{"tag_name":"1.2","prerelease":false,"extra":1,
               "assets":[{"name":"app.apk","browser_download_url":"https://example.invalid/app.apk","size":5,"other":true}]}""",
        )
        assertEquals("1.2", release.tag)
        assertEquals("app.apk", release.assets.single().name)
    }

    @Test
    fun singleApkIsPickedWithoutRules() {
        val picked = GitHubReleases.pickAsset(assets("notes.txt", "WatermelonDS-0.8.0.apk"), emptyList(), 33)
        assertEquals("WatermelonDS-0.8.0.apk", picked?.name)
    }

    @Test
    fun ruleSelectsTheNamedVariant() {
        val picked = GitHubReleases.pickAsset(
            assets("azahar-android-googleplay-1.apk", "azahar-android-vanilla-1.apk"),
            listOf(AssetRule("vanilla")),
            33,
        )
        assertEquals("azahar-android-vanilla-1.apk", picked?.name)
    }

    @Test
    fun sdkRulesChooseTheNewestBuildTheDeviceCanRun() {
        val files = assets(
            "ARMSX2-2.7-a11-armv8.2-sdk30.apk",
            "ARMSX2-2.7-a13-armv8.2-sdk33.apk",
            "ARMSX2-2.7-a15-armv8.2-sdk35.apk",
            "ARMSX2-2.7-legacy-armv8.0-sdk26.apk",
        )
        val rules = catalog.byId("ps2")!!.emulator.source!!.assets
        assertEquals("ARMSX2-2.7-a13-armv8.2-sdk33.apk", GitHubReleases.pickAsset(files, rules, 33)?.name)
        assertEquals("ARMSX2-2.7-a13-armv8.2-sdk33.apk", GitHubReleases.pickAsset(files, rules, 34)?.name)
        assertEquals("ARMSX2-2.7-a15-armv8.2-sdk35.apk", GitHubReleases.pickAsset(files, rules, 35)?.name)
        assertEquals("ARMSX2-2.7-a11-armv8.2-sdk30.apk", GitHubReleases.pickAsset(files, rules, 30)?.name)
    }

    @Test
    fun noApkMeansNothingIsPicked() {
        assertNull(GitHubReleases.pickAsset(assets("app.zip", "app.exe"), emptyList(), 33))
        assertNull(GitHubReleases.pickAsset(assets("other.apk"), listOf(AssetRule("vanilla")), 33))
    }

    @Test
    fun folderSystemsCountFoldersAsGames() {
        val card = temp.root
        File(card, "ROMs/ps3/Game One/PS3_GAME").mkdirs()
        File(card, "ROMs/ps3/Game Two").mkdirs()
        File(card, "ROMs/ps3/Game Three.iso").apply { parentFile?.mkdirs(); writeText("x") }
        File(card, "ROMs/ps3/readme.txt").writeText("x")
        assertEquals(3, CardScanner.scan(card, catalog).gamesFor("ps3"))
    }

    @Test
    fun plansOnlyMissingFoldersAndCreatesThem() {
        val card = temp.root
        File(card, "ROMs/nes").mkdirs()
        val scan = CardScanner.scan(card, catalog)
        val plan = FolderPlanner.plan(card, scan, catalog, setOf("nes", "snes", "ps2"))
        assertEquals(listOf("snes", "ps2"), plan.map { it.name })
        assertTrue(plan.all { it.parentFile?.name == "ROMs" })
        assertTrue(FolderPlanner.create(plan).isEmpty())
        assertTrue(File(card, "ROMs/snes").isDirectory)
        assertTrue(FolderPlanner.plan(card, CardScanner.scan(card, catalog), catalog, setOf("nes", "snes", "ps2")).isEmpty())
    }

    @Test
    fun emptyCardGetsARomsFolderPlanned() {
        val card = temp.root
        val plan = FolderPlanner.plan(card, CardScanner.scan(card, catalog), catalog, setOf("gba"))
        assertEquals(File(card, "ROMs/gba"), plan.single())
    }

    @Test
    fun chipsetCodesGetFriendlyNames() {
        assertEquals("Snapdragon 8 Gen 2 (QCS8550)", Chipsets.friendly("QTI QCS8550"))
        assertEquals("Snapdragon 8 Elite (SM8750)", Chipsets.friendly("Qualcomm SM8750"))
        assertEquals("MediaTek MT6893", Chipsets.friendly("MediaTek MT6893"))
    }
}

class AppMatcherTest {
    private fun emulator(name: String, vararg appNames: String) =
        app.loadout.data.Emulator(name = name, appNames = appNames.toList())

    @Test
    fun matchesByVisibleNameIgnoringCaseAndPunctuation() {
        assertTrue(app.loadout.install.AppMatcher.matches(emulator("Azahar"), "Azahar"))
        assertTrue(app.loadout.install.AppMatcher.matches(emulator("WatermelonDS"), "watermelon DS"))
        assertTrue(app.loadout.install.AppMatcher.matches(emulator("RetroArch (Mupen64Plus-Next)"), "RetroArch"))
    }

    @Test
    fun matchesListedAlternativeNames() {
        assertTrue(app.loadout.install.AppMatcher.matches(emulator("Dolphin", "Dolphin Emulator"), "Dolphin Emulator"))
        assertTrue(app.loadout.install.AppMatcher.matches(emulator("PPSSPP", "PPSSPP Gold"), "PPSSPP Gold"))
    }

    @Test
    fun doesNotMatchOtherApps() {
        assertEquals(false, app.loadout.install.AppMatcher.matches(emulator("Eden"), "Garden Planner"))
        assertEquals(false, app.loadout.install.AppMatcher.matches(emulator("Azahar"), ""))
        assertEquals(false, app.loadout.install.AppMatcher.matches(emulator("Dolphin"), "Dolphin Browser"))
    }
}
