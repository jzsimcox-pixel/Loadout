package app.loadout.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One setting a template would change. old is null when the setting is not in the file yet. */
data class SettingChange(val key: String, val old: String?, val new: String)

/** text: the whole file after merging. changes: empty when the file already matches. */
data class MergeResult(val text: String, val changes: List<SettingChange>)

/** Templates name device-specific values as {{NAME}}. */
object Placeholders {
    private val token = Regex("\\{\\{([A-Z0-9_]+)\\}\\}")

    fun fill(text: String, values: Map<String, String>): String =
        token.replace(text) { values[it.groupValues[1]] ?: it.value }

    fun unresolved(text: String): Boolean = token.containsMatchIn(text)

    /**
     * Fills in what is known and drops every line that still names something unknown,
     * so a setting Loadout cannot work out is left as the emulator has it.
     */
    fun fillLines(text: String, values: Map<String, String>): String =
        fill(text, values).split("\n").filterNot { unresolved(it) }.joinToString("\n")
}

/** A file as a list of lines that can be put back together with its own line endings. */
private class Lines(text: String) {
    private val eol = if (text.contains("\r\n")) "\r\n" else "\n"
    private val endsWithEol = text.isEmpty() || text.endsWith("\n")
    val list: MutableList<String> =
        if (text.isEmpty()) {
            ArrayList()
        } else {
            text.split("\n").map { it.removeSuffix("\r") }.toMutableList().also {
                if (text.endsWith("\n")) it.removeAt(it.size - 1)
            }
        }

    fun join(): String = if (list.isEmpty()) "" else list.joinToString(eol) + if (endsWithEol) eol else ""
}

/** RetroArch-style files: one `key = "value"` per line, no sections. */
object CfgMerge {
    private val entry = Regex("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.*?)\\s*$")

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun parse(line: String): Pair<String, String>? {
        if (line.isBlank() || line.trimStart().startsWith("#")) return null
        val match = entry.find(line) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** Sets every key in patch. Keys the patch does not mention are left exactly as they are. */
    fun merge(existing: String, patch: String): MergeResult {
        val lines = Lines(existing)
        val index = HashMap<String, Int>()
        lines.list.forEachIndexed { i, line ->
            parse(line)?.let { if (it.first !in index) index[it.first] = i }
        }
        val changes = ArrayList<SettingChange>()
        for (raw in patch.split("\n")) {
            val (key, value) = parse(raw.removeSuffix("\r")) ?: continue
            val at = index[key]
            if (at == null) {
                lines.list.add("$key = $value")
                index[key] = lines.list.size - 1
                changes += SettingChange(key, null, unquote(value))
            } else {
                val old = parse(lines.list[at])?.second.orEmpty()
                if (unquote(old) != unquote(value)) {
                    lines.list[at] = "$key = $value"
                    changes += SettingChange(key, unquote(old), unquote(value))
                }
            }
        }
        return MergeResult(lines.join(), changes)
    }
}

/** Files made of [Section] headers with key=value lines under them. */
object IniMerge {
    private val header = Regex("^\\s*\\[(.+)\\]\\s*$")

    private fun section(line: String): String? = header.find(line)?.groupValues?.get(1)

    private fun parse(line: String): Pair<String, String>? {
        val text = line.trim()
        if (text.isEmpty() || text[0] == '#' || text[0] == ';' || text[0] == '[' || text.startsWith("//")) return null
        val equals = text.indexOf('=')
        if (equals <= 0) return null
        return text.substring(0, equals).trim() to text.substring(equals + 1).trim()
    }

    /**
     * Sets every key in patch inside its section, adding the key or the whole section when
     * missing. Lines the patch does not mention are left exactly as they are.
     */
    fun merge(existing: String, patch: String): MergeResult {
        val wanted = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null
        for (raw in patch.split("\n")) {
            val line = raw.removeSuffix("\r")
            val name = section(line)
            if (name != null) {
                current = name
                wanted.getOrPut(name) { ArrayList() }
            } else if (current != null && parse(line) != null) {
                wanted.getValue(current).add(line.trim())
            }
        }

        val lines = Lines(existing)
        val changes = ArrayList<SettingChange>()
        for ((name, entries) in wanted) {
            if (entries.isEmpty()) continue
            var start = lines.list.indexOfFirst { section(it) == name }
            if (start < 0) {
                if (lines.list.isNotEmpty() && lines.list.last().isNotBlank()) lines.list.add("")
                lines.list.add("[$name]")
                start = lines.list.size - 1
            }
            // Where each key of this section sits, and where the section stops.
            var end = start + 1
            val index = HashMap<String, Int>()
            while (end < lines.list.size && section(lines.list[end]) == null) {
                parse(lines.list[end])?.let { if (it.first !in index) index[it.first] = end }
                end += 1
            }
            var insert = end
            while (insert > start + 1 && lines.list[insert - 1].isBlank()) insert -= 1

            for (entry in entries) {
                val (key, value) = parse(entry) ?: continue
                val at = index[key]
                if (at == null) {
                    lines.list.add(insert, entry)
                    index[key] = insert
                    insert += 1
                    changes += SettingChange("$name / $key", null, value)
                } else {
                    val old = parse(lines.list[at])?.second.orEmpty()
                    if (old != value) {
                        lines.list[at] = entry
                        changes += SettingChange("$name / $key", old, value)
                    }
                }
            }
        }
        return MergeResult(lines.join(), changes)
    }
}

/** JSON settings files. Objects are merged key by key; anything else is replaced whole. */
object JsonMerge {
    private val pretty = Json { prettyPrint = true }

    /** Throws when existing is not a JSON object, so a file Loadout cannot read is never rewritten. */
    fun merge(existing: String, patch: String): MergeResult {
        val wanted = Json.parseToJsonElement(patch).jsonObject
        val base = if (existing.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(existing).jsonObject
        val changes = ArrayList<SettingChange>()
        val merged = mergeObject(base, wanted, "", changes)
        // Keep the file's own layout: one line stays one line.
        val multiLine = existing.isBlank() || existing.trim().contains("\n")
        val encoder = if (multiLine) pretty else Json
        return MergeResult(encoder.encodeToString(JsonElement.serializer(), merged), changes)
    }

    private fun mergeObject(
        base: JsonObject,
        wanted: JsonObject,
        prefix: String,
        changes: MutableList<SettingChange>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in wanted) {
            val name = if (prefix.isEmpty()) key else "$prefix / $key"
            val old = base[key]
            if (value is JsonObject && (old == null || old is JsonObject)) {
                out[key] = mergeObject(old as? JsonObject ?: JsonObject(emptyMap()), value, name, changes)
            } else if (old == null || !same(old, value)) {
                out[key] = value
                changes += SettingChange(name, old?.let(::show), show(value))
            }
        }
        return JsonObject(out)
    }

    /** 1 and 1.0 are the same setting, so numbers are compared by value. */
    private fun same(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (a.isString != b.isString) return false
            if (a.isString) return a.content == b.content
            val x = a.content.toDoubleOrNull()
            val y = b.content.toDoubleOrNull()
            return if (x != null && y != null) x == y else a.content == b.content
        }
        return a == b
    }

    private fun show(element: JsonElement): String =
        if (element is JsonPrimitive) element.content else element.toString()
}
