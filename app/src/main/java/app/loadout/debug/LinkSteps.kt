package app.loadout.debug

/** What the setup guide needs to know to pick the next step. wireless is null when Android won't say. */
data class LinkChecks(
    val developer: Boolean,
    val wifi: Boolean,
    val wireless: Boolean?,
    val notifications: Boolean,
    val paired: Boolean,
    val connected: Boolean,
    val error: String? = null,
)

/** The steps of the wireless debugging guide, in the order the user meets them. */
enum class LinkStep { DEVELOPER, WIFI, WIRELESS, NOTIFICATIONS, PAIR, CONNECT, DONE }

object LinkSteps {
    /** The first thing still to do. */
    fun next(checks: LinkChecks): LinkStep = when {
        checks.connected -> LinkStep.DONE
        !checks.developer -> LinkStep.DEVELOPER
        !checks.wifi -> LinkStep.WIFI
        checks.wireless == false -> LinkStep.WIRELESS
        checks.paired -> LinkStep.CONNECT
        // The pairing code is typed into a notification, so that permission comes first.
        !checks.notifications -> LinkStep.NOTIFICATIONS
        else -> LinkStep.PAIR
    }
}
