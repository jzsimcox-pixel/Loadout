package app.loadout.storage

import app.loadout.data.Emulator
import app.loadout.data.SystemRecipe
import java.io.File

/** One way ES-DE can launch a system: the name shown in its menus and the launch command. */
data class EsdeCommand(val label: String, val command: String)

/** Reads ES-DE's es_systems.xml, the file that lists which emulators each system can use. */
object EsdeSystems {
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val system = Regex("<system>(.*?)</system>", RegexOption.DOT_MATCHES_ALL)
    private val name = Regex("<name>\\s*(.*?)\\s*</name>", RegexOption.DOT_MATCHES_ALL)
    private val command =
        Regex("<command(?:\\s+label=\"([^\"]*)\")?\\s*>(.*?)</command>", RegexOption.DOT_MATCHES_ALL)

    /** Maps ES-DE system name (nes, psx, ...) to its commands, in ES-DE's order. The first is the default. */
    fun parse(xml: String): Map<String, List<EsdeCommand>> {
        val result = LinkedHashMap<String, List<EsdeCommand>>()
        for (block in system.findAll(xml.replace(comment, ""))) {
            val body = block.groupValues[1]
            val systemName = name.find(body)?.groupValues?.get(1)?.lowercase() ?: continue
            val commands = command.findAll(body).map {
                EsdeCommand(unescape(it.groupValues[1]), it.groupValues[2].trim())
            }.toList()
            if (commands.isNotEmpty()) result[systemName] = commands
        }
        return result
    }

    private fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}

/** Works out which ES-DE command corresponds to one of Loadout's emulators. */
object EsdeMatcher {
    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    private val bracket = Regex("\\s*\\((.*)\\)$")
    private val token = Regex("%EMULATOR_([^%]+)%")

    /** Returns the matching command, or null when ES-DE does not list this emulator for the system. */
    fun find(emulator: Emulator, commands: List<EsdeCommand>): EsdeCommand? {
        val base = emulator.name.replace(bracket, "")
        val core = bracket.find(emulator.name)?.groupValues?.get(1)
        if (normalize(base) == "retroarch") {
            val retroarch = commands.filter { "%EMULATOR_RETROARCH%" in it.command }
            if (core == null) return retroarch.firstOrNull()
            return retroarch.firstOrNull { normalize(it.label) == normalize(core) }
        }
        val wanted = (listOf(base) + emulator.appNames).map(::normalize)
        return commands.firstOrNull { candidate ->
            if ("%EMULATOR_RETROARCH%" in candidate.command) return@firstOrNull false
            val label = normalize(candidate.label.replace(bracket, ""))
            val launcher = token.find(candidate.command)?.groupValues?.get(1)?.let(::normalize)
            (label.isNotEmpty() && label in wanted) || (label.isEmpty() && launcher in wanted)
        }
    }
}

/** Reads and edits the emulator choice ES-DE keeps at the top of a system's gamelist.xml. */
object Gamelists {
    private val block =
        Regex("<alternativeEmulator>\\s*<label>(.*?)</label>\\s*</alternativeEmulator>\\s*", RegexOption.DOT_MATCHES_ALL)
    private val declaration = Regex("^\\s*<\\?xml[^>]*\\?>\\s*")

    fun currentLabel(text: String): String? =
        block.find(text)?.groupValues?.get(1)?.trim()
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
            ?.ifEmpty { null }

    /** Returns the file text with the emulator choice set to label, or removed when label is null. */
    fun withLabel(text: String?, label: String?): String {
        val existing = text?.takeIf { it.isNotBlank() }
        val entry = label?.let {
            val safe = it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<alternativeEmulator>\n\t<label>$safe</label>\n</alternativeEmulator>\n"
        }.orEmpty()
        if (existing == null) return "<?xml version=\"1.0\"?>\n$entry<gameList />\n"
        if (block.containsMatchIn(existing)) return block.replaceFirst(existing, Regex.escapeReplacement(entry))
        val header = declaration.find(existing)
        return if (header != null) {
            existing.substring(0, header.range.last + 1).trimEnd() + "\n" + entry + existing.substring(header.range.last + 1)
        } else {
            "<?xml version=\"1.0\"?>\n$entry$existing"
        }
    }
}

/** Reads and edits the selected theme in ES-DE's es_settings.xml. Only an existing entry is changed. */
object EsdeSettings {
    private val theme = Regex("(<string\\s+name=\"Theme\"\\s+value=\")([^\"]*)(\")")

    fun currentTheme(text: String): String? = theme.find(text)?.groupValues?.get(2)

    /** Returns null when the file has no theme entry, in which case nothing should be written. */
    fun withTheme(text: String, folderName: String): String? {
        val match = theme.find(text) ?: return null
        val value = match.groups[2] ?: return null
        return text.substring(0, value.range.first) + folderName + text.substring(value.range.last + 1)
    }
}

enum class EsdeStatus { ALREADY, CHANGE, NOT_LISTED, NO_SYSTEM }

/** One system the user set up, with the emulator Loadout would have ES-DE use. */
data class EsdePick(val recipe: SystemRecipe, val emulator: Emulator, val folderName: String?)

data class EsdeRow(
    val systemId: String,
    val title: String,
    val emulator: String,
    val status: EsdeStatus,
    /** What ES-DE launches this system with today. */
    val current: String,
    /** The label to write. Null removes the override so ES-DE uses its default. */
    val target: String?,
    val file: File?,
    /** True when applying would replace a choice the user made inside ES-DE. */
    val replacesUserChoice: Boolean,
)

object EsdePlanner {
    const val BACKUP_SUFFIX = ".loadout-backup"

    fun plan(picks: List<EsdePick>, systems: Map<String, List<EsdeCommand>>, dataDir: File): List<EsdeRow> =
        picks.map { pick ->
            val title = "${pick.recipe.code} · ${pick.recipe.name}"
            val shown = pick.emulator.name
            val names = (listOfNotNull(pick.folderName) + pick.recipe.folders).map { it.lowercase() }
            val esdeName = names.firstOrNull { it in systems }
                ?: return@map EsdeRow(pick.recipe.id, title, shown, EsdeStatus.NO_SYSTEM, "", null, null, false)
            val commands = systems.getValue(esdeName)
            val file = File(dataDir, "gamelists/$esdeName/gamelist.xml")
            val override = if (file.isFile) {
                Gamelists.currentLabel(file.readText())?.takeIf { label -> commands.any { it.label == label } }
            } else {
                null
            }
            val default = commands.first()
            val effective = override ?: default.label
            val match = EsdeMatcher.find(pick.emulator, commands)
                ?: return@map EsdeRow(pick.recipe.id, title, shown, EsdeStatus.NOT_LISTED, effective, null, file, false)
            if (match.label == effective) {
                return@map EsdeRow(pick.recipe.id, title, shown, EsdeStatus.ALREADY, effective, null, file, false)
            }
            EsdeRow(
                systemId = pick.recipe.id,
                title = title,
                emulator = shown,
                status = EsdeStatus.CHANGE,
                current = effective,
                target = if (match == default) null else match.label,
                file = file,
                replacesUserChoice = override != null,
            )
        }

    /** Writes the changes. Each edited file is copied to a backup first, once. Returns the rows that failed. */
    fun apply(rows: List<EsdeRow>): List<EsdeRow> = rows.filter { row ->
        val file = row.file
        if (row.status != EsdeStatus.CHANGE || file == null) return@filter false
        try {
            file.parentFile?.mkdirs()
            val existing = if (file.isFile) file.readText() else null
            if (existing != null) backup(file)
            file.writeText(Gamelists.withLabel(existing, row.target))
            false
        } catch (_: Exception) {
            true
        }
    }

    /** Copies a file beside itself before its first edit. An existing backup is never overwritten. */
    fun backup(file: File) {
        val copy = File(file.parentFile, file.name + BACKUP_SUFFIX)
        if (!copy.exists()) file.copyTo(copy)
    }
}
