package cn.edu.cppu.libraryautobooker.booking

internal enum class WebVpnReloginDecision { ALLOWED, AFTER_SUBMISSION, ALREADY_TRIED }

internal fun webVpnReloginDecision(
    submitted: Boolean,
    automationStarted: Boolean,
    alreadyRecovered: Boolean
): WebVpnReloginDecision = when {
    submitted -> WebVpnReloginDecision.AFTER_SUBMISSION
    automationStarted || alreadyRecovered -> WebVpnReloginDecision.ALREADY_TRIED
    else -> WebVpnReloginDecision.ALLOWED
}
