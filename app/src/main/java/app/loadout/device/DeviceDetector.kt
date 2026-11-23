package app.loadout.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build

data class DeviceInfo(
    val maker: String,
    val model: String,
    val chipset: String,
    val screens: Int,
    val profileId: String,
    val displayName: String,
) {
    val dualScreen: Boolean get() = screens >= 2
}

object DeviceProfiles {
    /**
     * Maps what the device reports about itself to a known profile.
     * Returns the profile id, a friendly name, and the number of built-in screens.
     * Unknown devices fall back to a generic profile.
     */
    fun match(maker: String, model: String, reportedScreens: Int): Triple<String, String, Int> {
        val text = "$maker $model".lowercase()
        return when {
            "thor" in text -> Triple("ayn-thor", "AYN Thor", 2)
            "odin" in text && "portal" in text && "3" in text ->
                Triple("ayn-odin3-portal", "AYN Odin 3 Portal", 1)
            "odin" in text && "portal" in text ->
                Triple("ayn-odin2-portal", "AYN Odin 2 Portal", 1)
            reportedScreens >= 2 -> Triple("generic-dual", "$maker $model".trim(), 2)
            else -> Triple("generic-single", "$maker $model".trim(), 1)
        }
    }
}

object Chipsets {
    private val names = mapOf(
        "8250" to "Snapdragon 865",
        "8350" to "Snapdragon 888",
        "8450" to "Snapdragon 8 Gen 1",
        "8475" to "Snapdragon 8+ Gen 1",
        "8550" to "Snapdragon 8 Gen 2",
        "8650" to "Snapdragon 8 Gen 3",
        "8750" to "Snapdragon 8 Elite",
    )

    /** Turns a raw code such as "QTI QCS8550" into "Snapdragon 8 Gen 2 (QCS8550)". */
    fun friendly(raw: String): String {
        val code = raw.trim().split(" ").lastOrNull().orEmpty()
        val digits = Regex("(?:SM|QCS|QCM)(\\d{4})").find(code.uppercase())?.groupValues?.get(1)
        val name = names[digits] ?: return raw
        return "$name ($code)"
    }
}

object DeviceDetector {
    fun detect(context: Context): DeviceInfo {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val chipset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
                .ifBlank { Build.HARDWARE.orEmpty() }
        } else {
            Build.HARDWARE.orEmpty()
        }
        val displays = context.getSystemService(DisplayManager::class.java)?.displays?.size ?: 1
        val (profileId, name, screens) = DeviceProfiles.match(maker, model, displays)
        return DeviceInfo(
            maker = maker,
            model = model,
            chipset = Chipsets.friendly(chipset).ifBlank { "Unknown" },
            screens = screens,
            profileId = profileId,
            displayName = name.ifBlank { "Android device" },
        )
    }
}
