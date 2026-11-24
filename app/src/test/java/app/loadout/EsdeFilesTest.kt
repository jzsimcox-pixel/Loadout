package app.loadout

import app.loadout.data.Emulator
import app.loadout.data.Recipes
import app.loadout.storage.EsdeMatcher
import app.loadout.storage.EsdePick
import app.loadout.storage.EsdePlanner
import app.loadout.storage.EsdeSettings
import app.loadout.storage.EsdeStatus
import app.loadout.storage.EsdeSystems
import app.loadout.storage.Gamelists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    private val xml = """
        <?xml version="1.0"?>
        <systemList>
            <system>
                <name>ps2</name>
                <fullname>Sony PlayStation 2</fullname>
                <command label="NetherSX2 (Standalone)">%EMULATOR_NETHERSX2% %EXTRA_bootPath%=%ROMSAF%</command>
                <command label="ARMSX2 (Standalone)">%EMULATOR_ARMSX2% %DATA%=%ROMSAF%</command>
                <!-- <command label="Old (Standalone)">%EMULATOR_OLD% %ROM%</command> -->
                <command label="Play! (Standalone)">%EMULATOR_PLAY!% %DATA%=%ROMSAF%</command>
            </system>
            <system>
                <name>n64</name>
                <command label="Mupen64Plus-Next">%EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=mupen64plus_next_gles3_libretro_android.so %EXTRA_ROM%=%ROM%</command>
                <command label="M64Plus FZ (Standalone)">%EMULATOR_M64PLUS-FZ% %DATA%=%ROMSAF%</command>
            </system>
            <system>
                <name>nes</name>
                <command label="Mesen">%EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=mesen_libretro_android.so %EXTRA_ROM%=%ROM%</command>
                <command label="Nestopia UE">%EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=nestopia_libretro_android.so %EXTRA_ROM%=%ROM%</command>
            </system>
            <system>
                <name>wiiu</name>
                <command>%EMULATOR_CEMU% %DATA%=%ROMSAF%</command>
            </system>
        </systemList>
    """.trimIndent()

    private val systems = EsdeSystems.parse(xml)

    private fun pick(id: String, emulator: Emulator? = null, folder: String? = null): EsdePick {
        val recipe = catalog.byId(id)!!
        return EsdePick(recipe, emulator ?: recipe.emulator, folder)
    }

    @Test
    fun parsesSystemsInOrderAndSkipsCommentedCommands() {
        assertEquals(setOf("ps2", "n64", "nes", "wiiu"), systems.keys)
        assertEquals(
            listOf("NetherSX2 (Standalone)", "ARMSX2 (Standalone)", "Play! (Standalone)"),
            systems.getValue("ps2").map { it.label },
        )
        assertEquals("", systems.getValue("wiiu").single().label)
    }

    @Test
    fun matchesStandaloneCoreAndPlainRetroArch() {
        assertEquals("ARMSX2 (Standalone)", EsdeMatcher.find(Emulator("ARMSX2"), systems.getValue("ps2"))?.label)
        assertEquals(
            "Mupen64Plus-Next",
            EsdeMatcher.find(Emulator("RetroArch (Mupen64Plus-Next)"), systems.getValue("n64"))?.label,
        )
        assertEquals(
            "M64Plus FZ (Standalone)",
            EsdeMatcher.find(Emulator("Mupen64Plus FZ", appNames = listOf("M64Plus FZ")), systems.getValue("n64"))?.label,
        )
        assertEquals("Mesen", EsdeMatcher.find(Emulator("RetroArch"), systems.getValue("nes"))?.label)
        assertEquals("", EsdeMatcher.find(Emulator("Cemu"), systems.getValue("wiiu"))?.label)
        assertNull(EsdeMatcher.find(Emulator("WatermelonDS"), systems.getValue("ps2")))
    }

    @Test
    fun gamelistLabelIsCreatedReplacedInsertedAndRemoved() {
        val created = Gamelists.withLabel(null, "ARMSX2 (Standalone)")
        assertEquals("ARMSX2 (Standalone)", Gamelists.currentLabel(created))
        assertTrue(created.startsWith("<?xml version=\"1.0\"?>"))
        assertTrue(created.trimEnd().endsWith("<gameList />"))

        val replaced = Gamelists.withLabel(created, "Play! (Standalone)")
        assertEquals("Play! (Standalone)", Gamelists.currentLabel(replaced))
        assertEquals(1, Regex("<alternativeEmulator>").findAll(replaced).count())

        val games = "<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./A.iso</path>\n\t</game>\n</gameList>\n"
        val inserted = Gamelists.withLabel(games, "ARMSX2 (Standalone)")
        assertEquals("ARMSX2 (Standalone)", Gamelists.currentLabel(inserted))
        assertTrue(inserted.contains("<path>./A.iso</path>"))
        assertTrue(inserted.indexOf("<alternativeEmulator>") < inserted.indexOf("<gameList>"))

        val removed = Gamelists.withLabel(inserted, null)
        assertNull(Gamelists.currentLabel(removed))
        assertEquals(games, removed)
    }

    @Test
    fun plansEachKindOfRow() {
        val data = temp.newFolder("ES-DE")
        val rows = EsdePlanner.plan(
            listOf(
                pick("ps2"),                       // ARMSX2 is not ES-DE's default
                pick("n64"),                       // recommended core is ES-DE's default
                pick("nes"),                       // plain RetroArch, default is a RetroArch core
                pick("nds"),                       // system missing from this ES-DE list
                pick("wiiu"),                      // single unlabelled command
                pick("ps3"),                       // system missing
            ),
            systems,
            data,
        ).associateBy { it.systemId }
        assertEquals(EsdeStatus.CHANGE, rows.getValue("ps2").status)
        assertEquals("ARMSX2 (Standalone)", rows.getValue("ps2").target)
        assertEquals("NetherSX2 (Standalone)", rows.getValue("ps2").current)
        assertFalse(rows.getValue("ps2").replacesUserChoice)
        assertEquals(EsdeStatus.ALREADY, rows.getValue("n64").status)
        assertEquals(EsdeStatus.ALREADY, rows.getValue("nes").status)
        assertEquals(EsdeStatus.NO_SYSTEM, rows.getValue("nds").status)
        assertEquals(EsdeStatus.ALREADY, rows.getValue("wiiu").status)
        assertEquals(EsdeStatus.NO_SYSTEM, rows.getValue("ps3").status)
    }

    @Test
    fun unlistedEmulatorIsReportedNotWritten() {
        val data = temp.newFolder("ES-DE")
        val row = EsdePlanner.plan(listOf(pick("ps2", Emulator("SomethingNew"))), systems, data).single()
        assertEquals(EsdeStatus.NOT_LISTED, row.status)
        assertTrue(EsdePlanner.apply(listOf(row)).isEmpty())
        assertFalse(File(data, "gamelists/ps2/gamelist.xml").exists())
    }

    @Test
    fun applyWritesBacksUpOnceAndKeepsGames() {
        val data = temp.newFolder("ES-DE")
        val file = File(data, "gamelists/ps2/gamelist.xml")
        file.parentFile?.mkdirs()
        val original = "<?xml version=\"1.0\"?>\n<alternativeEmulator>\n\t<label>Play! (Standalone)</label>\n</alternativeEmulator>\n<gameList>\n\t<game>\n\t\t<path>./A.iso</path>\n\t</game>\n</gameList>\n"
        file.writeText(original)

        val row = EsdePlanner.plan(listOf(pick("ps2")), systems, data).single()
        assertEquals(EsdeStatus.CHANGE, row.status)
        assertEquals("Play! (Standalone)", row.current)
        assertTrue(row.replacesUserChoice)
        assertTrue(EsdePlanner.apply(listOf(row)).isEmpty())

        assertEquals("ARMSX2 (Standalone)", Gamelists.currentLabel(file.readText()))
        assertTrue(file.readText().contains("<path>./A.iso</path>"))
        val backup = File(file.parentFile, "gamelist.xml.loadout-backup")
        assertEquals(original, backup.readText())

        // Going back to ES-DE's default removes the override, and the first backup is kept.
        val back = EsdePlanner.plan(listOf(pick("ps2", Emulator("NetherSX2"))), systems, data).single()
        assertNull(back.target)
        assertTrue(EsdePlanner.apply(listOf(back)).isEmpty())
        assertNull(Gamelists.currentLabel(file.readText()))
        assertEquals(original, backup.readText())
        assertEquals(EsdeStatus.ALREADY, EsdePlanner.plan(listOf(pick("ps2", Emulator("NetherSX2"))), systems, data).single().status)
    }

    @Test
    fun usesTheFolderNameEsdeKnows() {
        val data = temp.newFolder("ES-DE")
        val megadrive = EsdeSystems.parse(
            "<systemList><system><name>megadrive</name><command label=\"Genesis Plus GX\">%EMULATOR_RETROARCH% x</command></system></systemList>",
        )
        val row = EsdePlanner.plan(listOf(pick("genesis", folder = "MegaDrive")), megadrive, data).single()
        assertEquals(EsdeStatus.ALREADY, row.status)
        assertEquals(File(data, "gamelists/megadrive/gamelist.xml"), row.file)
    }

    @Test
    fun themeIsOnlyChangedWhenTheSettingExists() {
        val settings = "<?xml version=\"1.0\"?>\n<bool name=\"A\" value=\"true\" />\n<string name=\"Theme\" value=\"linear-es-de\" />\n"
        assertEquals("linear-es-de", EsdeSettings.currentTheme(settings))
        val updated = EsdeSettings.withTheme(settings, "art-book-next-es-de")!!
        assertEquals("art-book-next-es-de", EsdeSettings.currentTheme(updated))
        assertTrue(updated.contains("<bool name=\"A\" value=\"true\" />"))
        assertNull(EsdeSettings.withTheme("<?xml version=\"1.0\"?>\n<bool name=\"A\" value=\"true\" />\n", "x"))
    }

    @Test
    fun plainRetroArchIsSatisfiedByAnyCoreTheUserChose() {
        val data = temp.newFolder("ES-DE")
        val file = File(data, "gamelists/nes/gamelist.xml")
        file.parentFile?.mkdirs()
        file.writeText(Gamelists.withLabel(null, "Nestopia UE"))
        val row = EsdePlanner.plan(listOf(pick("nes")), systems, data).single()
        assertEquals(EsdeStatus.ALREADY, row.status)
        assertEquals("Nestopia UE", row.current)
    }

    @Test
    fun adoptsAnAlternateTheUserChoseInEsde() {
        val data = temp.newFolder("ES-DE")
        val file = File(data, "gamelists/ps2/gamelist.xml")
        file.parentFile?.mkdirs()
        file.writeText(Gamelists.withLabel(null, "ARMSX2 (Standalone)"))
        // In this sample ES-DE list NetherSX2 is the default, so make it the recommended emulator.
        val ps2 = catalog.byId("ps2")!!
        val swapped = ps2.copy(emulator = Emulator("NetherSX2"), alternates = listOf(Emulator("ARMSX2")))
        val adopted = EsdePlanner.adopt(listOf(swapped to null, catalog.byId("n64")!! to null), systems, data)
        assertEquals(mapOf("ps2" to Emulator("ARMSX2")), adopted)
    }

    @Test
    fun addsAnUnlistedEmulatorThroughCustomSystems() {
        val data = temp.newFolder("ES-DE")
        val spec = app.loadout.data.EsdeSpec(
            label = "NewEmu (Standalone)",
            find = "NEWEMU",
            command = "%EMULATOR_NEWEMU% %EXTRA_path%=%ROM%",
            packageId = "org.example.newemu",
            activity = "org.example.Main",
        )
        val emulator = Emulator("NewEmu", esde = spec)
        val row = EsdePlanner.plan(listOf(pick("ps2", emulator).copy(installedPackage = "org.example.real")), systems, data).single()
        assertEquals(EsdeStatus.ADDABLE, row.status)
        assertEquals("org.example.real/org.example.Main", row.addition?.entry)
        assertTrue(EsdePlanner.applyAll(listOf(row), data, xml).isEmpty())

        val custom = File(data, "custom_systems/es_systems.xml").readText()
        val merged = EsdeSystems.parse(custom).getValue("ps2").map { it.label }
        assertEquals(
            listOf("NetherSX2 (Standalone)", "ARMSX2 (Standalone)", "Play! (Standalone)", "NewEmu (Standalone)"),
            merged,
        )
        assertTrue(custom.contains("<fullname>Sony PlayStation 2</fullname>"))
        val rules = File(data, "custom_systems/es_find_rules.xml").readText()
        assertTrue(rules.contains("<emulator name=\"NEWEMU\">"))
        assertTrue(rules.contains("<entry>org.example.real/org.example.Main</entry>"))
        assertEquals("NewEmu (Standalone)", Gamelists.currentLabel(File(data, "gamelists/ps2/gamelist.xml").readText()))

        // With the custom file merged in, ES-DE now lists it and nothing more is needed.
        val after = EsdePlanner.plan(listOf(pick("ps2", emulator)), systems + EsdeSystems.parse(custom), data).single()
        assertEquals(EsdeStatus.ALREADY, after.status)
    }

    @Test
    fun customFilesAreExtendedNotReplaced() {
        val data = temp.newFolder("ES-DE")
        val dir = File(data, "custom_systems")
        dir.mkdirs()
        val mine = "<?xml version=\"1.0\"?>\n<systemList>\n\t<system>\n\t\t<name>mine</name>\n\t\t<command label=\"A\">%EMULATOR_A% %ROM%</command>\n\t</system>\n</systemList>\n"
        File(dir, "es_systems.xml").writeText(mine)
        val rules = "<?xml version=\"1.0\"?>\n<ruleList>\n\t<emulator name=\"A\">\n\t\t<rule type=\"androidpackage\">\n\t\t\t<entry>a/b</entry>\n\t\t</rule>\n\t</emulator>\n</ruleList>\n"
        File(dir, "es_find_rules.xml").writeText(rules)
        val add = app.loadout.storage.EsdeAddition("ps2", "NewEmu (Standalone)", "%EMULATOR_NEWEMU% %ROM%", "NEWEMU", "p/a")
        assertTrue(app.loadout.storage.EsdeCustom.apply(data, xml, add))
        val systemsNow = EsdeSystems.parse(File(dir, "es_systems.xml").readText())
        assertEquals(setOf("mine", "ps2"), systemsNow.keys)
        val rulesNow = File(dir, "es_find_rules.xml").readText()
        assertTrue(rulesNow.contains("<emulator name=\"A\">") && rulesNow.contains("<emulator name=\"NEWEMU\">"))
        assertEquals(mine, File(dir, "es_systems.xml.loadout-backup").readText())
        assertEquals(rules, File(dir, "es_find_rules.xml.loadout-backup").readText())
        // Applying twice adds nothing a second time.
        assertTrue(app.loadout.storage.EsdeCustom.apply(data, xml, add))
        assertEquals(1, Regex("NewEmu").findAll(File(dir, "es_systems.xml").readText()).count())
    }
}
