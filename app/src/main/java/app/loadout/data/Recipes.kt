package app.loadout.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Picks one download out of a release that offers several.
 * match: text (a regular expression) the file name must contain.
 * minSdk: only use this rule on devices at or above this Android API level.
 */
@Serializable
data class AssetRule(
    val match: String,
    val minSdk: Int = 0,
)

/** Where an emulator is downloaded from. Only "github" is supported so far. */
@Serializable
data class Source(
    val type: String,
    val repo: String,
    val assets: List<AssetRule> = emptyList(),
)

@Serializable
data class Emulator(
    val name: String,
    /** Null means Loadout cannot install this emulator yet. */
    val source: Source? = null,
    /** Other names the installed app may show under, such as a paid edition. */
    val appNames: List<String> = emptyList(),
)

/**
 * One system Loadout can set up.
 *
 * folders: names ES-DE uses for this system's ROM folder, most common first.
 * extensions: lower-case file types counted as games.
 * kind: cart, disc, handheld or dual. Only used to pick an icon.
 * support: full, install-first or experimental.
 * folderGames: games are stored as one folder each, so folders count as games.
 */
@Serializable
data class SystemRecipe(
    val id: String,
    val code: String,
    val name: String,
    val kind: String,
    val folders: List<String>,
    val extensions: List<String>,
    val emulator: Emulator,
    val alternates: List<Emulator> = emptyList(),
    val support: String = "full",
    val dualScreen: Boolean = false,
    val folderGames: Boolean = false,
)

@Serializable
data class Catalog(
    val version: Int,
    val systems: List<SystemRecipe>,
) {
    fun byId(id: String): SystemRecipe? = systems.firstOrNull { it.id == id }
}

object Recipes {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Catalog = json.decodeFromString(Catalog.serializer(), text)
}
