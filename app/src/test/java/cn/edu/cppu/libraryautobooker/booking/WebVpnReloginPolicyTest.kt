package cn.edu.cppu.libraryautobooker.booking

import org.junit.Assert.assertEquals
import org.junit.Test

class WebVpnReloginPolicyTest {
    @Test fun submissionLockWinsOverEveryOtherRecoveryState() {
        listOf(false, true).forEach { automation ->
            listOf(false, true).forEach { recovered ->
                assertEquals(WebVpnReloginDecision.AFTER_SUBMISSION,
                    webVpnReloginDecision(submitted = true, automationStarted = automation,
                        alreadyRecovered = recovered))
            }
        }
    }

    @Test fun recoveryIsOnlyAllowedBeforeAutomationAndOnlyOnce() {
        assertEquals(WebVpnReloginDecision.ALLOWED,
            webVpnReloginDecision(submitted = false, automationStarted = false, alreadyRecovered = false))
        assertEquals(WebVpnReloginDecision.ALREADY_TRIED,
            webVpnReloginDecision(submitted = false, automationStarted = true, alreadyRecovered = false))
        assertEquals(WebVpnReloginDecision.ALREADY_TRIED,
            webVpnReloginDecision(submitted = false, automationStarted = false, alreadyRecovered = true))
    }
}
