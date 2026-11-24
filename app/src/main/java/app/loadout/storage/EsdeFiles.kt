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

    fun stripComments(xml: String): String = xml.replace(comment, "")

    /** Maps ES-DE system name (nes, psx, ...) to its commands, in ES-DE's order. The first is the default. */
    fun parse(xml: String): Map<String, List<EsdeCommand>> {
        val result = LinkedHashMap<String, List<EsdeCommand>>()
        for (block in system.findAll(stripComments(xml))) {
            val body = block.groupValues[1]
            val systemName = name.find(body)?.groupValues?.get(1)?.lowercase() ?: continue
            val commands = command.findAll(body).map {
                EsdeCommand(unescape(it.groupValues[1]), unescape(it.groupValues[2].trim()))
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

    /**
     * True when this ES-DE command launches the given emulator. Plain "RetroArch" is satisfied
     * by any RetroArch core; "RetroArch (Core)" only by that core.
     */
    fun satisfies(emulator: Emulator, command: EsdeCommand): Boolean {
        val base = emulator.name.replace(bracket, "")
        val core = bracket.find(emulator.name)?.groupValues?.get(1)
        val isRetroArch = "%EMULATOR_RETROARCH%" in command.command
        if (normalize(base) == "retroarch") {
            return isRetroArch && (core == null || normalize(command.label) == normalize(core))
        }
        if (isRetroArch) return false
        val wanted = (listOf(base) + emulator.appNames).map(::normalize)
        val label = normalize(command.label.replace(bracket, ""))
        val launcher = token.find(command.command)?.groupValues?.get(1)?.let(::normalize)
        return (label.isNotEmpty() && label in wanted) || (launcher != null && launcher in wanted)
    }

    /** Returns the first matching command, or null when ES-DE does not list this emulator for the system. */
    fun find(emulator: Emulator, commands: List<EsdeCommand>): EsdeCommand? =
        commands.firstOrNull { satisfies(emulator, it) }
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

/** An emulator to teach ES-DE about, for one system. entry is "package/activity". */
data class EsdeAddition(
    val system: String,
    val label: String,
    val command: String,
    val findName: String,
    val entry: String,
)

/**
 * Adds an emulator ES-DE does not know to ES-DE's custom systems files. A custom system
 * definition replaces the bundled one, so the bundled definition is copied and extended.
 */
object EsdeCustom {
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun blockFor(xml: String, system: String): MatchResult? =
        Regex(
            "<system>(?:(?!</system>).)*?<name>\\s*" + Regex.escape(system) + "\\s*</name>.*?</system>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(xml)

    private fun insertCommand(block: String, line: String): String? {
        val end = block.lastIndexOf("</command>")
        if (end < 0) return null
        val cut = end + "</command>".length
        return block.substring(0, cut) + "\n" + line + block.substring(cut)
    }

    /** New text for custom_systems/es_systems.xml, or null when the system cannot be found anywhere. */
    fun withCommand(customXml: String?, bundledXml: String, add: EsdeAddition): String? {
        val line = "\t\t<command label=\"${escape(add.label).replace("\"", "&quot;")}\">${escape(add.command)}</command>"
        val custom = customXml?.takeIf { it.isNotBlank() }
        val existing = custom?.let { blockFor(it, add.system) }
        if (custom != null && existing != null) {
            if ("label=\"${add.label}\"" in existing.value) return custom
            val updated = insertCommand(existing.value, line) ?: return null
            return custom.replaceRange(existing.range, updated)
        }
        val bundled = blockFor(EsdeSystems.stripComments(bundledXml), add.system)?.value ?: return null
        val block = insertCommand(bundled, line) ?: return null
        if (custom == null) return "<?xml version=\"1.0\"?>\n<systemList>\n\t$block\n</systemList>\n"
        val end = custom.lastIndexOf("</systemList>")
        if (end < 0) return null
        return custom.substring(0, end) + "\t" + block + "\n" + custom.substring(end)
    }

    /** New text for custom_systems/es_find_rules.xml, or null when the existing file cannot be extended. */
    fun withFindRule(customRules: String?, add: EsdeAddition): String? {
        val block = "\t<emulator name=\"${add.findName}\">\n\t\t<rule type=\"androidpackage\">\n" +
            "\t\t\t<entry>${escape(add.entry)}</entry>\n\t\t</rule>\n\t</emulator>\n"
        val rules = customRules?.takeIf { it.isNotBlank() }
            ?: return "<?xml version=\"1.0\"?>\n<ruleList>\n$block</ruleList>\n"
        if ("<emulator name=\"${add.findName}\">" in rules) return rules
        val end = rules.lastIndexOf("</ruleList>")
        if (end < 0) return null
        return rules.substring(0, end) + block + rules.substring(end)
    }

    /** Writes both custom files, backing up any that exist. Nothing is written unless both can be. */
    fun apply(dataDir: File, bundledXml: String, add: EsdeAddition): Boolean {
        val dir = File(dataDir, "custom_systems")
        val systemsFile = File(dir, "es_systems.xml")
        val rulesFile = File(dir, "es_find_rules.xml")
        return try {
            val systems = withCommand(systemsFile.takeIf { it.isFile }?.readText(), bundledXml, add) ?: return false
            val rules = withFindRule(rulesFile.takeIf { it.isFile }?.readText(), add) ?: return false
            dir.mkdirs()
            if (systemsFile.isFile) EsdePlanner.backup(systemsFile)
            if (rulesFile.isFile) EsdePlanner.backup(rulesFile)
            systemsFile.writeText(systems)
            rulesFile.writeText(rules)
            true
        } catch (_: Exception) {
            false
        }
    }
}

enum class EsdeStatus { ALREADY, CHANGE, ADDABLE, NOT_LISTED, NO_SYSTEM }

/** One system the user set up, with the emulator Loadout would have ES-DE use. */
data class EsdePick(
    val recipe: SystemRecipe,
    val emulator: Emulator,
    val folderName: String?,
    /** The emulator's package on this device, when Loadout has found it installed. */
    val installedPackage: String? = null,
)

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
    /** Set when the emulator must first be added to ES-DE's custom systems. */
    val addition: EsdeAddition? = null,
)

object EsdePlanner {
    const val BACKUP_SUFFIX = ".loadout-backup"

    private fun esdeName(recipe: SystemRecipe, folderName: String?, systems: Map<String, List<EsdeCommand>>): String? =
        (listOfNotNull(folderName) + recipe.folders).map { it.lowercase() }.firstOrNull { it in systems }

    private fun gamelist(dataDir: File, esdeName: String) = File(dataDir, "gamelists/$esdeName/gamelist.xml")

    /** The command ES-DE uses for a system today: the user's override if valid, else the default. */
    private fun effective(commands: List<EsdeCommand>, file: File): Pair<EsdeCommand, Boolean> {
        val label = if (file.isFile) Gamelists.currentLabel(file.readText()) else null
        val override = label?.let { wanted -> commands.firstOrNull { it.label == wanted } }
        return (override ?: commands.first()) to (override != null)
    }

    fun plan(picks: List<EsdePick>, systems: Map<String, List<EsdeCommand>>, dataDir: File): List<EsdeRow> =
        picks.map { pick ->
            val title = "${pick.recipe.code} · ${pick.recipe.name}"
            val shown = pick.emulator.name
            val name = esdeName(pick.recipe, pick.folderName, systems)
                ?: return@map EsdeRow(pick.recipe.id, title, shown, EsdeStatus.NO_SYSTEM, "", null, null, false)
            val commands = systems.getValue(name)
            val file = gamelist(dataDir, name)
            val (current, isOverride) = effective(commands, file)
            if (EsdeMatcher.satisfies(pick.emulator, current)) {
                return@map EsdeRow(pick.recipe.id, title, shown, EsdeStatus.ALREADY, current.label, null, file, false)
            }
            val match = EsdeMatcher.find(pick.emulator, commands)
            if (match == null) {
                val spec = pick.emulator.esde
                    ?: return@map EsdeRow(pick.recipe.id, title, shown, EsdeStatus.NOT_LISTED, current.label, null, file, false)
                val addition = EsdeAddition(
                    system = name,
                    label = spec.label,
                    command = spec.command,
                    findName = spec.find,
                    entry = "${pick.installedPackage ?: spec.packageId}/${spec.activity}",
                )
                return@map EsdeRow(
                    pick.recipe.id, title, shown, EsdeStatus.ADDABLE, current.label, spec.label, file, isOverride, addition,
                )
            }
            EsdeRow(
                systemId = pick.recipe.id,
                title = title,
                emulator = shown,
                status = EsdeStatus.CHANGE,
                current = current.label,
                target = if (match == commands.first()) null else match.label,
                file = file,
                replacesUserChoice = isOverride,
            )
        }

    /**
     * For systems where the user picked an emulator inside ES-DE, returns which of Loadout's
     * options that is, so Loadout can show the same choice. Systems on the recommended one are left out.
     */
    fun adopt(
        recipes: List<Pair<SystemRecipe, String?>>,
        systems: Map<String, List<EsdeCommand>>,
        dataDir: File,
    ): Map<String, Emulator> {
        val result = LinkedHashMap<String, Emulator>()
        for ((recipe, folderName) in recipes) {
            val name = esdeName(recipe, folderName, systems) ?: continue
            val (current, _) = effective(systems.getValue(name), gamelist(dataDir, name))
            if (EsdeMatcher.satisfies(recipe.emulator, current)) continue
            recipe.alternates.firstOrNull { EsdeMatcher.satisfies(it, current) }?.let { result[recipe.id] = it }
        }
        return result
    }

    /** Applies rows that only change the emulator choice. Returns the rows that failed. */
    fun apply(rows: List<EsdeRow>): List<EsdeRow> = applyAll(rows, null, null)

    /**
     * Writes the changes. Rows that add an emulator need the data folder and ES-DE's bundled
     * systems file. Each edited file is copied to a backup first, once. Returns the rows that failed.
     */
    fun applyAll(rows: List<EsdeRow>, dataDir: File?, bundledXml: String?): List<EsdeRow> = rows.filter { row ->
        val file = row.file
        if (file == null || (row.status != EsdeStatus.CHANGE && row.status != EsdeStatus.ADDABLE)) return@filter false
        try {
            if (row.status == EsdeStatus.ADDABLE) {
                val addition = row.addition
                if (addition == null || dataDir == null || bundledXml == null) return@filter true
                if (!EsdeCustom.apply(dataDir, bundledXml, addition)) return@filter true
            }
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
