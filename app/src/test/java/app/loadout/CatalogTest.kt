package app.loadout

import app.loadout.data.Recipes
import app.loadout.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    private val catalog = Recipes.parse(File("src/main/assets/recipes.json").readText())

    @Test
    fun catalogHasTwentyEightUniqueSystems() {
        assertEquals(28, catalog.systems.size)
        assertEquals(28, catalog.systems.map { it.id }.toSet().size)
        assertEquals(28, catalog.systems.map { it.code }.toSet().size)
    }

    @Test
    fun everySystemHasFolderExtensionsAndEmulator() {
        for (system in catalog.systems) {
            assertTrue(system.id, system.folders.isNotEmpty())
            assertTrue(system.id, system.extensions.isNotEmpty())
            assertTrue(system.id, system.emulator.name.isNotBlank())
            assertTrue(system.id, system.kind in setOf("cart", "disc", "handheld", "dual"))
            assertTrue(system.id, system.support in setOf("full", "install-first", "experimental"))
        }
    }

    @Test
    fun noTwoSystemsShareAFolderName() {
        val all = catalog.systems.flatMap { it.folders.map(String::lowercase) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun dualScreenSystemsAreDsAnd3ds() {
        assertEquals(setOf("nds", "n3ds"), catalog.systems.filter { it.dualScreen }.map { it.id }.toSet())
    }

    @Test
    fun everySourceIsUsable() {
        val emulators = catalog.systems.flatMap { listOf(it.emulator) + it.alternates }
        for (emulator in emulators) {
            val source = emulator.source ?: continue
            when (source.type) {
                "github" -> assertTrue(emulator.name, Regex("[\\w.-]+/[\\w.-]+").matches(source.repo))
                "gitea" -> {
                    assertTrue(emulator.name, source.host.isNotBlank())
                    assertTrue(emulator.name, Regex("[\\w.-]+/[\\w.-]+").matches(source.repo))
                }
                "page" -> assertTrue(emulator.name, source.url.startsWith("https://"))
                else -> throw AssertionError("Unknown source type for ${emulator.name}: ${source.type}")
            }
            source.assets.forEach { Regex(it.match) }
        }
    }

    @Test
    fun knownDevicesMatchTheirProfiles() {
        assertEquals("ayn-thor", DeviceProfiles.match("AYN", "Thor", 2).first)
        assertEquals(2, DeviceProfiles.match("AYN", "Thor", 1).third)
        assertEquals("ayn-odin2-portal", DeviceProfiles.match("AYN", "Odin2 Portal", 1).first)
        assertEquals("ayn-odin3-portal", DeviceProfiles.match("AYN", "Odin 3 Portal", 1).first)
        assertEquals("generic-single", DeviceProfiles.match("Acme", "Pocket", 1).first)
        assertEquals("generic-dual", DeviceProfiles.match("Acme", "Flip", 2).first)
    }
}
