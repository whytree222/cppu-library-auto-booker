package cn.edu.cppu.libraryautobooker.ocr

import org.junit.Assert.*
import org.junit.Test

class CaptchaPagePolicyTest {
    @Test fun onlyTheVerifiedHttpsLoginResourcesAreAccepted() {
        assertTrue(CaptchaPagePolicy.isTrusted("https://sso.cppu.edu.cn/tpass/login?service=example"))
        assertTrue(CaptchaPagePolicy.isTrusted("https://webvpn.cppu.edu.cn${CaptchaPagePolicy.SSO_PROXY_PREFIX}/tpass/login"))
        listOf("http://sso.cppu.edu.cn/tpass/login", "https://sso.cppu.edu.cn.evil.test/tpass/login",
            "https://evil.cppu.edu.cn/tpass/login", "https://sso.cppu.edu.cn:444/tpass/login",
            "https://x@sso.cppu.edu.cn/tpass/login", "https://sso.cppu.edu.cn/tpass/%6Cogin",
            "https://webvpn.cppu.edu.cn/login", "https://webvpn.cppu.edu.cn/https/other/tpass/login",
            "https://sso.cppu.edu.cn/tpass/login/other", "not a url").forEach {
            assertFalse(it, CaptchaPagePolicy.isTrusted(it))
        }
    }

    @Test fun FourDigitsRequireUnrestrictedAgreementAndConservativeSignal() {
        assertTrue(CaptchaPagePolicy.accepts("0011", "0011", 0.9))
        assertFalse(CaptchaPagePolicy.accepts("0011", "OO11", 0.9))
        assertFalse(CaptchaPagePolicy.accepts("0011", "0011", 0.69))
        assertFalse(CaptchaPagePolicy.accepts("0011", "0011", Double.NaN))
        assertFalse(CaptchaPagePolicy.accepts("123", "123", 0.99))
        assertFalse(CaptchaPagePolicy.accepts("１２３４", "１２３４", 0.99))
    }

    @Test fun NoRepeatedInferenceOrMoreThanThreeImagesWithoutExplicitReset() {
        val budget = CaptchaAttemptBudget(1000)
        assertEquals(CaptchaAttemptBudget.Decision.START, budget.begin("a", 1001))
        assertEquals(CaptchaAttemptBudget.Decision.ALREADY_TRIED, budget.begin("a", 1002))
        assertEquals(CaptchaAttemptBudget.Decision.START, budget.begin("b", 1003))
        assertEquals(CaptchaAttemptBudget.Decision.START, budget.begin("c", 1004))
        assertEquals(CaptchaAttemptBudget.Decision.LIMIT, budget.begin("d", 1005))
        assertEquals(CaptchaAttemptBudget.Decision.EXPIRED, budget.begin("d", 181000))
        assertEquals(CaptchaAttemptBudget.Decision.START, CaptchaAttemptBudget(181000).begin("d", 181001))
    }
}
