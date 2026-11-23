package app.loadout.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Emulator(
    val name: String,
)

/**
 * One system Loadout can set up.
 *
 * folders: names ES-DE uses for this system's ROM folder, most common first.
 * extensions: lower-case file types counted as games.
 * kind: cart, disc, handheld or dual. Only used to pick an icon.
 * support: full, install-first or experimental.
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
