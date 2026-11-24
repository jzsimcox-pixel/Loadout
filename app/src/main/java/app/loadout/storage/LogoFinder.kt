package app.loadout.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import app.loadout.data.Catalog
import com.caverock.androidsvg.SVG
import java.io.File

/**
 * Finds system logo images that are already on the device and prepares them for display.
 * Loadout ships no logos of its own. It looks in two places:
 *  - "Loadout/icons": images the user put there, named after the system folder (nes.png, ps2.svg).
 *  - "ES-DE/themes": the logos inside whichever ES-DE themes are installed.
 */
object LogoIndex {
    private val EXTENSIONS = listOf("svg", "png", "webp")
    private const val MAX_DEPTH = 8

    /** Folders to search, user-supplied icons first so they win over theme logos. */
    fun searchRoots(volumes: List<File>): List<File> =
        (volumes.map { File(it, "Loadout/icons") } + volumes.map { File(it, "ES-DE/themes") })
            .filter { it.isDirectory }

    /** Maps system id to the best logo file found. Read-only. */
    fun find(roots: List<File>, catalog: Catalog): Map<String, File> {
        val wanted = HashMap<String, String>()
        for (system in catalog.systems) {
            for (name in system.folders + system.id) wanted.putIfAbsent(name.lowercase(), system.id)
        }
        val found = HashMap<String, File>()
        for (root in roots) {
            val userIcons = root.name.equals("icons", ignoreCase = true)
            walk(root, 0) { file, insideLogoFolder ->
                val systemId = wanted[file.nameWithoutExtension.lowercase()] ?: return@walk
                if (file.extension.lowercase() !in EXTENSIONS) return@walk
                if (!userIcons && !insideLogoFolder) return@walk
                val current = found[systemId]
                if (current == null || rank(file) < rank(current) && current.parentFile == file.parentFile) {
                    found[systemId] = file
                }
            }
        }
        return found
    }

    private fun rank(file: File): Int = EXTENSIONS.indexOf(file.extension.lowercase())

    private fun walk(dir: File, depth: Int, insideLogoFolder: Boolean = false, visit: (File, Boolean) -> Unit) {
        val entries = dir.listFiles() ?: return
        for (entry in entries.sortedBy { it.name.lowercase() }) {
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                if (depth < MAX_DEPTH) {
                    walk(entry, depth + 1, insideLogoFolder || "logo" in entry.name.lowercase(), visit)
                }
            } else if (entry.isFile) {
                visit(entry, insideLogoFolder)
            }
        }
    }
}

object LogoRenderer {
    /** Draws a logo file into a bitmap no larger than the given box, keeping its shape. */
    fun render(file: File, maxWidth: Int, maxHeight: Int): Bitmap? =
        try {
            if (file.extension.equals("svg", ignoreCase = true)) renderSvg(file, maxWidth, maxHeight)
            else renderRaster(file, maxWidth, maxHeight)
        } catch (_: Throwable) {
            null
        }

    private fun renderSvg(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        var box = svg.documentViewBox
        if (box == null) {
            val width = svg.documentWidth
            val height = svg.documentHeight
            if (width <= 0f || height <= 0f) return null
            svg.setDocumentViewBox(0f, 0f, width, height)
            box = svg.documentViewBox ?: return null
        }
        val scale = minOf(maxWidth / box.width(), maxHeight / box.height())
        val width = (box.width() * scale).toInt().coerceAtLeast(1)
        val height = (box.height() * scale).toInt().coerceAtLeast(1)
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun renderRaster(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
