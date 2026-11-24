package app.loadout.install

import app.loadout.data.AssetRule
import app.loadout.data.Source
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

object GitHubReleases {
    private val json = Json { ignoreUnknownKeys = true }

    /** Address of the "latest release" description. GitHub and Gitea answer in the same shape. */
    fun latestUrl(source: Source): String =
        if (source.type == "gitea") {
            "https://${source.host}/api/v1/repos/${source.repo}/releases/latest"
        } else {
            "https://api.github.com/repos/${source.repo}/releases/latest"
        }

    fun parse(text: String): Release = json.decodeFromString(Release.serializer(), text)

    /**
     * Chooses which APK to download.
     *
     * With rules: the first rule this device qualifies for that matches a file wins.
     * Without rules: the only APK, or failing that one built for 64-bit ARM.
     */
    fun pickAsset(assets: List<ReleaseAsset>, rules: List<AssetRule>, sdk: Int): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        if (rules.isNotEmpty()) {
            for (rule in rules) {
                if (sdk < rule.minSdk) continue
                val pattern = Regex(rule.match, RegexOption.IGNORE_CASE)
                apks.firstOrNull { pattern.containsMatchIn(it.name) }?.let { return it }
            }
            return null
        }
        if (apks.size == 1) return apks.first()
        val arm64 = Regex("arm64|aarch64|armv8", RegexOption.IGNORE_CASE)
        return apks.firstOrNull { arm64.containsMatchIn(it.name) } ?: apks.first()
    }
}
