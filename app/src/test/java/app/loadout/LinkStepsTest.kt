package app.loadout

import app.loadout.debug.LinkChecks
import app.loadout.debug.LinkStep
import app.loadout.debug.LinkSteps
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStepsTest {
    private val fresh = LinkChecks(
        developer = false, wifi = false, wireless = false, notifications = false, paired = false, connected = false,
    )

    @Test
    fun aNewDeviceWalksThroughEveryStepInOrder() {
        var checks = fresh
        assertEquals(LinkStep.DEVELOPER, LinkSteps.next(checks))
        checks = checks.copy(developer = true)
        assertEquals(LinkStep.WIFI, LinkSteps.next(checks))
        checks = checks.copy(wifi = true)
        assertEquals(LinkStep.WIRELESS, LinkSteps.next(checks))
        checks = checks.copy(wireless = true)
        assertEquals(LinkStep.NOTIFICATIONS, LinkSteps.next(checks))
        checks = checks.copy(notifications = true)
        assertEquals(LinkStep.PAIR, LinkSteps.next(checks))
        checks = checks.copy(paired = true)
        assertEquals(LinkStep.CONNECT, LinkSteps.next(checks))
        checks = checks.copy(connected = true)
        assertEquals(LinkStep.DONE, LinkSteps.next(checks))
    }

    @Test
    fun afterARebootOnlyWirelessDebuggingIsLeft() {
        val checks = fresh.copy(developer = true, wifi = true, notifications = true, paired = true)
        assertEquals(LinkStep.WIRELESS, LinkSteps.next(checks))
    }

    @Test
    fun whenAndroidWontSayIfWirelessDebuggingIsOnTheGuideMovesOn() {
        val checks = fresh.copy(developer = true, wifi = true, wireless = null, notifications = true)
        assertEquals(LinkStep.PAIR, LinkSteps.next(checks))
    }

    @Test
    fun aPairedDeviceDoesNotNeedNotifications() {
        val checks = fresh.copy(developer = true, wifi = true, wireless = true, paired = true)
        assertEquals(LinkStep.CONNECT, LinkSteps.next(checks))
    }
}
