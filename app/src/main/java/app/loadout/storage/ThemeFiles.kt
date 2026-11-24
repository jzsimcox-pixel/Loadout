package app.loadout.storage

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** File work for installing a theme. No Android dependencies, so it can be tested directly. */
object ThemeFiles {
    /**
     * Where a new theme should go: the themes folder of the ES-DE data folder in use,
     * or a new "ES-DE/themes" on the first volume when ES-DE has not been set up yet.
     */
    fun themesDir(volumes: List<File>): File {
        val candidates = LogoIndex.esdeDirs(volumes)
        val inUse = candidates.firstOrNull { File(it, "settings/es_settings.xml").isFile }
            ?: candidates.firstOrNull()
        if (inUse != null) return LogoIndex.customThemeDir(inUse) ?: File(inUse, "themes")
        return File(volumes.first(), "ES-DE/themes")
    }

    /**
     * Unpacks a GitHub source archive into target. Such archives hold everything inside
     * one top-level folder, which is dropped. Entries that would land outside target are refused.
     */
    fun unzip(zip: File, target: File) {
        val base = target.canonicalFile
        if (!base.isDirectory && !base.mkdirs()) throw IOException("Could not create the theme folder.")
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val out = File(base, relative).canonicalFile
                if (!out.path.startsWith(base.path + File.separator)) {
                    throw IOException("The theme archive contains an unsafe path.")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
