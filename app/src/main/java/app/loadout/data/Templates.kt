package app.loadout.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One settings file Loadout can tune.
 *
 * template: the file inside assets/templates.
 * root: a folder the emulator creates itself. Loadout only writes when it already exists.
 * path: the settings file inside root.
 * mode: merge-cfg, merge-ini, merge-json or replace.
 * optional: when set, the file belongs to an extra the user has to choose, such as a shader.
 */
@Serializable
data class TemplateFile(
    val template: String,
    val root: String,
    val path: String,
    val mode: String,
    val optional: String = "",
)

@Serializable
data class EmulatorTemplates(
    val files: List<TemplateFile> = emptyList(),
)

/**
 * profile: the device the settings were tuned on. emulators: keyed by emulator name,
 * without any "(core)" suffix.
 */
@Serializable
data class TemplateManifest(
    val version: Int,
    val profile: String = "",
    val profileName: String = "",
    val emulators: Map<String, EmulatorTemplates> = emptyMap(),
)

object Templates {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): TemplateManifest = json.decodeFromString(TemplateManifest.serializer(), text)
}
