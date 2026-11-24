package app.loadout

import app.loadout.storage.CfgMerge
import app.loadout.storage.IniMerge
import app.loadout.storage.JsonMerge
import app.loadout.storage.Placeholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsMergeTest {
    // ------------------------------------------------------------ placeholders

    @Test
    fun knownPlaceholdersAreFilledAndUnknownLinesDropped() {
        val template = "a = \"{{INTERNAL}}/x\"\nb = \"{{BIOS_DIR}}\"\nc = \"1\""
        val filled = Placeholders.fillLines(template, mapOf("INTERNAL" to "/storage/emulated/0"))
        assertEquals("a = \"/storage/emulated/0/x\"\nc = \"1\"", filled)
        assertFalse(Placeholders.unresolved(filled))
    }

    @Test
    fun placeholderNamesMayContainDigits() {
        assertTrue(Placeholders.unresolved("BIOS = {{PS2_BIOS_FILE}}"))
        assertEquals("x\nz", Placeholders.fillLines("x\nBIOS = {{PS2_BIOS_FILE}}\nz", emptyMap()))
        assertEquals("BIOS = a.bin", Placeholders.fill("BIOS = {{PS2_BIOS_FILE}}", mapOf("PS2_BIOS_FILE" to "a.bin")))
    }

    // --------------------------------------------------------------------- cfg

    @Test
    fun cfgChangesOnlyTheKeysInThePatch() {
        val existing = "video_driver = \"gl\"\nmy_own_setting = \"keep\"\nfps_show = \"true\"\n"
        val result = CfgMerge.merge(existing, "# comment\nvideo_driver = \"vulkan\"\nfps_show = \"true\"\n")
        assertEquals("video_driver = \"vulkan\"\nmy_own_setting = \"keep\"\nfps_show = \"true\"\n", result.text)
        assertEquals(1, result.changes.size)
        assertEquals("video_driver", result.changes[0].key)
        assertEquals("gl", result.changes[0].old)
        assertEquals("vulkan", result.changes[0].new)
    }

    @Test
    fun cfgAddsMissingKeysAtTheEnd() {
        val result = CfgMerge.merge("a = \"1\"\n", "b = \"2\"")
        assertEquals("a = \"1\"\nb = \"2\"\n", result.text)
        assertNull(result.changes.single().old)
    }

    @Test
    fun cfgThatAlreadyMatchesIsLeftByteForByte() {
        val existing = "a = \"1\"\r\nb=2\r\n"
        val result = CfgMerge.merge(existing, "a = \"1\"\nb = \"2\"")
        assertTrue(result.changes.isEmpty())
        assertEquals(existing, result.text)
    }

    @Test
    fun cfgKeepsWindowsLineEndings() {
        val result = CfgMerge.merge("a = \"1\"\r\nb = \"2\"\r\n", "b = \"3\"")
        assertEquals("a = \"1\"\r\nb = \"3\"\r\n", result.text)
    }

    @Test
    fun cfgIntoAnEmptyFileWritesEveryKey() {
        val result = CfgMerge.merge("", "a = \"1\"\nb = \"2\"")
        assertEquals("a = \"1\"\nb = \"2\"\n", result.text)
        assertEquals(2, result.changes.size)
    }

    // --------------------------------------------------------------------- ini

    private val dolphin = "[Core]\nGFXBackend = OGL\nMine = 7\n[Interface]\nUsePanicHandlers = True\n"

    @Test
    fun iniChangesAKeyInsideItsSection() {
        val result = IniMerge.merge(dolphin, "[Core]\nGFXBackend = Vulkan\n")
        assertEquals("[Core]\nGFXBackend = Vulkan\nMine = 7\n[Interface]\nUsePanicHandlers = True\n", result.text)
        assertEquals("Core / GFXBackend", result.changes.single().key)
        assertEquals("OGL", result.changes.single().old)
    }

    @Test
    fun iniAddsAMissingKeyAtTheEndOfItsSection() {
        val result = IniMerge.merge(dolphin, "[Core]\nEnableCheats = True\n")
        assertEquals(
            "[Core]\nGFXBackend = OGL\nMine = 7\nEnableCheats = True\n[Interface]\nUsePanicHandlers = True\n",
            result.text,
        )
    }

    @Test
    fun iniAddsAKeyBeforeTheBlankLinesThatEndASection() {
        val result = IniMerge.merge("[A]\nx=1\n\n\n[B]\ny=2\n", "[A]\nz=3\n")
        assertEquals("[A]\nx=1\nz=3\n\n\n[B]\ny=2\n", result.text)
    }

    @Test
    fun iniAddsAMissingSection() {
        val result = IniMerge.merge(dolphin, "[DSP]\nDSPThread = True\n")
        assertTrue(result.text.endsWith("UsePanicHandlers = True\n\n[DSP]\nDSPThread = True\n"))
        assertEquals("DSP / DSPThread", result.changes.single().key)
    }

    @Test
    fun iniSameKeyInAnotherSectionIsNotTouched() {
        val existing = "[Pad1]\nType = None\n[Pad2]\nType = None\n"
        val result = IniMerge.merge(existing, "[Pad1]\nType = DualShock2\n")
        assertEquals("[Pad1]\nType = DualShock2\n[Pad2]\nType = None\n", result.text)
    }

    @Test
    fun iniHandlesEdenStyleKeysWithBackslashes() {
        val existing = "[System]\nuse_docked_mode\\default=true\nuse_docked_mode=0\n"
        val patch = "# note\n\n[System]\nuse_docked_mode\\default=false\nuse_docked_mode=1\n"
        val result = IniMerge.merge(existing, patch)
        assertEquals("[System]\nuse_docked_mode\\default=false\nuse_docked_mode=1\n", result.text)
        assertEquals(2, result.changes.size)
    }

    @Test
    fun iniThatAlreadyMatchesIsLeftByteForByte() {
        val result = IniMerge.merge(dolphin, "[Core]\nGFXBackend = OGL\n[Interface]\nUsePanicHandlers = True\n")
        assertTrue(result.changes.isEmpty())
        assertEquals(dolphin, result.text)
    }

    @Test
    fun iniIntoAnEmptyFileWritesEverySection() {
        val result = IniMerge.merge("", "[A]\nx = 1\n[B]\ny = 2\n")
        assertEquals("[A]\nx = 1\n\n[B]\ny = 2\n", result.text)
    }

    // -------------------------------------------------------------------- json

    @Test
    fun jsonMergesNestedObjectsAndKeepsOtherKeys() {
        val existing = """{"global":{"renderer":"opengl","upscaleFloat":1,"mine":true},"games":{"A":{"x":1}}}"""
        val result = JsonMerge.merge(existing, """{"global":{"renderer":"vulkan","upscaleFloat":2.5}}""")
        assertEquals(
            """{"global":{"renderer":"vulkan","upscaleFloat":2.5,"mine":true},"games":{"A":{"x":1}}}""",
            result.text,
        )
        assertEquals(listOf("global / renderer", "global / upscaleFloat"), result.changes.map { it.key })
    }

    @Test
    fun jsonTreatsEqualNumbersAsEqual() {
        val existing = """{"global":{"a":1,"b":59.94,"c":true}}"""
        val result = JsonMerge.merge(existing, """{"global":{"a":1.0,"b":59.94,"c":true}}""")
        assertTrue(result.changes.isEmpty())
        assertEquals(existing, result.text)
    }

    @Test
    fun jsonRefusesAFileItCannotRead() {
        try {
            JsonMerge.merge("not json at all", """{"a":1}""")
            fail("expected a failure")
        } catch (_: Exception) {
            // Good: the planner turns this into "left alone".
        }
    }

    @Test
    fun jsonIntoAnEmptyFileListsEverySetting() {
        val result = JsonMerge.merge("", """{"global":{"a":1,"b":"x"}}""")
        assertEquals(listOf("global / a", "global / b"), result.changes.map { it.key })
    }
}
