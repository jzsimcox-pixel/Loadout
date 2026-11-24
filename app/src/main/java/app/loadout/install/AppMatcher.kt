package app.loadout.install

import app.loadout.data.Emulator

/** Decides whether an installed app is the emulator a recipe names, going by the app's visible name. */
object AppMatcher {
    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /** "RetroArch (Mupen64Plus-Next)" is the app "RetroArch" using a particular core. */
    fun baseName(emulatorName: String): String =
        emulatorName.replace(Regex("\\s*\\(.*\\)$"), "")

    fun matches(emulator: Emulator, appLabel: String): Boolean {
        val label = normalize(appLabel)
        if (label.isEmpty()) return false
        val wanted = (listOf(baseName(emulator.name)) + emulator.appNames).map(::normalize)
        if (label in wanted) return true
        // Builds often add a qualifier in brackets, such as "RetroArch (AArch64)".
        val withoutBrackets = normalize(appLabel.replace(Regex("\\s*[\\(\\[].*$"), ""))
        return withoutBrackets.isNotEmpty() && withoutBrackets in wanted
    }
}
