package cn.edu.cppu.libraryautobooker.network

import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.ocr.DdddOcrEngine
import org.junit.Assert.*
import org.junit.Test

class NativeWebVpnRecoveryTest {
    @Test fun validSessionDoesNotReadAccountsRecognizeOrSubmitCredentials() = Fixture().use { f ->
        f.validAtLoad = true
        assertEquals("valid", f.recovery.run().state)
        assertEquals(0, f.accountReads); assertEquals(0, f.recognitions); assertTrue(f.posts.isEmpty())
        assertTrue(f.verified.isEmpty())
    }

    @Test fun twoLayerRecoveryUsesTheCorrespondingAccountAndConfirmsBoth() = Fixture().use { f ->
        assertEquals("valid", f.recovery.run().state)
        assertEquals(listOf(Kind.SSO, Kind.LIBRARY), f.posts.map { it.kind })
        assertEquals(listOf("synthetic-sso", "synthetic-library"), f.posts.map { it.username })
        assertEquals(listOf("1234", ""), f.posts.map { it.captcha })
        assertEquals(setOf(Kind.SSO, Kind.LIBRARY), f.verified)
        assertTrue(f.paused.isEmpty()); assertEquals(1, f.recognitions)
        assertTrue(f.images.all { image -> image.all { it == 0.toByte() } })
        assertTrue(f.statuses.any { it.contains("1/3") })
    }

    @Test fun libraryOnlyRecoveryRequiresNoSsoPermissionOrOcr() = Fixture(allowSso = false).use { f ->
        f.libraryAtLoad = true
        assertEquals("valid", f.recovery.run().state)
        assertEquals(listOf(Kind.LIBRARY), f.posts.map { it.kind })
        assertEquals(0, f.recognitions)
        assertEquals(setOf(Kind.LIBRARY), f.verified)
    }

    @Test fun outerExpiredAndAutoSsoDisabledDoesNotReadOrSendSavedCredentials() = Fixture(allowSso = false).use { f ->
        assertEquals("vpn_login_required", f.recovery.run().state)
        assertTrue(f.posts.isEmpty()); assertEquals(0, f.recognitions); assertEquals(0, f.accountReads)
    }

    @Test fun lowConfidenceThenValidFreshImageWaitsBeforeReloadAndSendsOnlyOnce() = Fixture().use { f ->
        f.results.add(bad()); f.results.add(good())
        assertEquals("valid", f.recovery.run().state)
        assertEquals(2, f.loads); assertEquals(2, f.recognitions)
        assertEquals(1_500L, f.slept)
        assertEquals(1, f.posts.count { it.kind == Kind.SSO }); assertTrue(f.paused.isEmpty())
    }

    @Test fun threeUnreliableImagesStopWithoutPausingTheCorrectPassword() = Fixture().use { f ->
        repeat(3) { f.results.add(bad()) }
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(3, f.loads); assertEquals(3, f.recognitions); assertEquals(3_000L, f.slept)
        assertTrue(f.posts.isEmpty()); assertTrue(f.paused.isEmpty())
    }

    @Test fun explicitCaptchaRejectionReusesReturnedFormAndNeverSendsAnOldOneAgain() = Fixture().use { f ->
        f.rejectCaptchas = 1
        assertEquals("valid", f.recovery.run().state)
        assertEquals(1, f.loads); assertEquals(2, f.recognitions); assertEquals(1_500L, f.slept)
        val sso = f.posts.filter { it.kind == Kind.SSO }
        assertEquals(2, sso.size); assertNotSame(sso[0].form, sso[1].form)
        assertTrue(f.paused.isEmpty()); assertEquals(setOf(Kind.SSO, Kind.LIBRARY), f.verified)
    }

    @Test fun threeExplicitCaptchaRejectionsStopWithoutASecondLibraryAttempt() = Fixture().use { f ->
        f.rejectCaptchas = 10
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(3, f.recognitions); assertEquals(3, f.posts.size)
        assertTrue(f.posts.all { it.kind == Kind.SSO }); assertTrue(f.paused.isEmpty())
    }

    @Test fun unsupportedRecognitionDoesNotFilterLettersIntoAValidCaptcha() = Fixture().use { f ->
        repeat(3) { f.results.add(DdddOcrEngine.Result("1234", .99, "a1234", 1)) }
        assertNotEquals("valid", f.recovery.run().state)
        assertTrue(f.posts.isEmpty()); assertEquals(3, f.recognitions)
    }

    @Test fun credentialRejectionPausesOnlyTheSubmittedAccount() = Fixture().use { f ->
        f.failureKind = NativeVpnFailureKind.CREDENTIAL_REJECTED
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(1, f.posts.size); assertEquals(setOf(Kind.SSO), f.paused)
        assertTrue(f.verified.isEmpty())
    }

    @Test fun libraryRejectionDoesNotReplaySsoOrLibraryAndKeepsSsoConfirmed() = Fixture().use { f ->
        f.failOnlyLibrary = true
        f.failureKind = NativeVpnFailureKind.CREDENTIAL_REJECTED
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(listOf(Kind.SSO, Kind.LIBRARY), f.posts.map { it.kind })
        assertEquals(setOf(Kind.LIBRARY), f.paused); assertEquals(setOf(Kind.SSO), f.verified)
    }

    @Test fun rateLimitAndExtraAuthenticationNeverTriggerCaptchaRetries() {
        for (kind in listOf(NativeVpnFailureKind.RATE_LIMITED, NativeVpnFailureKind.EXTRA_VERIFICATION)) Fixture().use { f ->
            f.failureKind = kind
            assertNotEquals("valid", f.recovery.run().state)
            assertEquals(1, f.posts.size); assertEquals(1, f.recognitions); assertEquals(0L, f.slept)
            assertTrue(f.paused.isEmpty())
        }
    }

    @Test fun postTimeoutStopsAndPausesTheConsumedAccountWithoutLeakingExceptionText() = Fixture().use { f ->
        f.unknownPost = true
        val result = f.recovery.run()
        assertNotEquals("valid", result.state); assertEquals(1, f.posts.size)
        assertEquals(setOf(Kind.SSO), f.paused)
        assertFalse(result.detail.contains("synthetic")); assertFalse(result.detail.contains("ticket="))
    }

    @Test fun typedThrownRejectionsKeepTheirClassificationAndNeverBecomeUnknownRetry() {
        for (kind in listOf(NativeVpnFailureKind.CAPTCHA_REJECTED, NativeVpnFailureKind.CREDENTIAL_REJECTED,
            NativeVpnFailureKind.RATE_LIMITED, NativeVpnFailureKind.EXTRA_VERIFICATION)) Fixture().use { f ->
            f.thrownKind = kind
            val result = f.recovery.run()
            assertNotEquals("valid", result.state); assertEquals(1, f.posts.size)
            assertFalse(result.detail.contains("synthetic"))
            assertFalse(result.detail.contains("结果未确认"))
            assertEquals(if (kind == NativeVpnFailureKind.CREDENTIAL_REJECTED) setOf(Kind.SSO) else emptySet<Kind>(), f.paused)
        }
    }

    @Test fun unknownReturnedStepDoesNotRetryAndPausesItsConsumedAccount() = Fixture().use { f ->
        f.failureKind = NativeVpnFailureKind.UNKNOWN_RESULT
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(1, f.posts.size); assertEquals(setOf(Kind.SSO), f.paused)
    }

    @Test fun changingAccountDuringRecognitionDiscardsResultBeforeAnyPost() = Fixture().use { f ->
        f.onRecognize = { f.revisions[Kind.SSO] = "replacement" }
        assertNotEquals("valid", f.recovery.run().state)
        assertTrue(f.posts.isEmpty()); assertTrue(f.verified.isEmpty()); assertTrue(f.paused.isEmpty())
    }

    @Test fun cancellingDuringRecognitionDoesNotWaitForOrCloseTheReaderOnThatThread() = Fixture().use { f ->
        f.onRecognize = { f.recovery.cancel(); assertFalse(f.readerClosed) }
        assertNotEquals("valid", f.recovery.run().state)
        assertTrue(f.posts.isEmpty()); assertTrue(f.paused.isEmpty())
        f.recovery.close(); assertTrue(f.readerClosed)
    }

    @Test fun cookieOrFormChangesDuringRecognitionDiscardTheRecognizedCode() = Fixture().use { f ->
        f.onRecognize = { f.formCurrent = false }
        assertNotEquals("valid", f.recovery.run().state)
        assertTrue(f.posts.isEmpty()); assertTrue(f.paused.isEmpty())
    }

    @Test fun finalSendGuardChecksAccountRevisionAgainBeforeConsumption() = Fixture().use { f ->
        f.beforeGuard = { f.revisions[Kind.SSO] = "replacement" }
        assertNotEquals("valid", f.recovery.run().state)
        assertTrue(f.posts.isEmpty()); assertTrue(f.paused.isEmpty())
    }

    @Test fun accountChangeAfterThePostCannotMarkNewCredentialsVerifiedOrContinueToLibrary() = Fixture().use { f ->
        f.afterSsoPost = { f.revisions[Kind.SSO] = "replacement" }
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(1, f.posts.size); assertTrue(f.verified.isEmpty()); assertTrue(f.paused.isEmpty())
    }

    @Test fun cancellationAfterPostDoesNotResendAndPausesOnlyUnconfirmedSubmission() = Fixture().use { f ->
        f.afterSsoPost = { f.recovery.cancel() }
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(1, f.posts.size); assertEquals(setOf(Kind.SSO), f.paused)
        assertTrue(f.verified.isEmpty())
    }

    @Test fun ssoAccountChangedWhileMarkingConfirmationCannotContinueWithLibraryPassword() = Fixture().use { f ->
        f.onVerified = { if (it == Kind.SSO) f.revisions[Kind.SSO] = "replacement" }
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(listOf(Kind.SSO), f.posts.map { it.kind })
        assertFalse(Kind.LIBRARY in f.verified)
    }

    @Test fun timeBudgetIncludesRecognitionAndNeverExtendsOnRefresh() = Fixture().use { f ->
        f.onRecognize = { f.time += 90_000 }
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(1, f.loads); assertEquals(1, f.recognitions); assertTrue(f.posts.isEmpty())
    }

    @Test fun missingLibraryAccountStopsAfterSsoWithoutMarkingUnsubmittedLibraryPassword() = Fixture().use { f ->
        f.enabled[Kind.LIBRARY] = false
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(listOf(Kind.SSO), f.posts.map { it.kind }); assertEquals(setOf(Kind.SSO), f.verified)
    }

    @Test fun cancelledBeforeRunNeverLoadsOrTouchesCredentials() = Fixture().use { f ->
        f.recovery.cancel()
        assertNotEquals("valid", f.recovery.run().state)
        assertEquals(0, f.loads); assertEquals(0, f.accountReads); assertTrue(f.posts.isEmpty())
    }

    @Test fun closingAfterSuccessDoesNotPausePreviouslyConfirmedPasswords() = Fixture().use { f ->
        assertEquals("valid", f.recovery.run().state)
        f.recovery.close(); f.recovery.close()
        assertTrue(f.paused.isEmpty()); assertEquals(1, f.readerCloseCount)
        assertTrue(f.recovery.awaitQuiescence(1_000))
    }

    @Test fun accountDiagnosticsNeverExposeSecrets() {
        val value = NativeRecoveryAccount("revision", "synthetic-user", "synthetic-secret")
        assertFalse(value.toString().contains("synthetic"))
    }

    private class Post(val kind: Kind, val form: NativeVpnForm, val username: String, val captcha: String)
    private class Fixture(allowSso: Boolean = true) : AutoCloseable {
        var time = 100L
        var owner = true
        var validAtLoad = false
        var libraryAtLoad = false
        var formCurrent = true
        var rejectCaptchas = 0
        var failureKind = NativeVpnFailureKind.NONE
        var failOnlyLibrary = false
        var unknownPost = false
        var thrownKind = NativeVpnFailureKind.NONE
        var loads = 0
        var accountReads = 0
        var recognitions = 0
        var slept = 0L
        var readerClosed = false
        var readerCloseCount = 0
        var onRecognize: () -> Unit = {}
        var beforeGuard: () -> Unit = {}
        var afterSsoPost: () -> Unit = {}
        var onVerified: (Kind) -> Unit = {}
        val revisions = Kind.entries.associateWith { "${it.name}-r1" }.toMutableMap()
        val enabled = Kind.entries.associateWith { true }.toMutableMap()
        val paused = mutableSetOf<Kind>()
        val verified = mutableSetOf<Kind>()
        val posts = mutableListOf<Post>()
        val results = ArrayDeque<DdddOcrEngine.Result>()
        val images = mutableListOf<ByteArray>()
        val statuses = mutableListOf<String>()
        private var active: NativeVpnForm? = null
        private var closed = false

        private fun step(kind: NativeVpnForm.Kind, failure: NativeVpnFailureKind = NativeVpnFailureKind.NONE): NativeVpnStep {
            val form = NativeVpnForm(kind, "synthetic-action", emptyMap(), "synthetic-binding", time)
            active = form
            val image = if (kind == NativeVpnForm.Kind.SSO) byteArrayOf(1, 2, 3).also { images.add(it) } else null
            return NativeVpnStep(form, image, SessionChecker.Result(
                if (kind == NativeVpnForm.Kind.SSO) "vpn_login_required" else "expired", "synthetic-response",
                vpnAuthState = if (kind == NativeVpnForm.Kind.SSO) "expired" else "valid"),
                error = failure != NativeVpnFailureKind.NONE, failureKind = failure)
        }
        private val session = object : NativeRecoverySession {
            override fun load(): NativeVpnStep {
                check(!closed); loads++
                return if (validAtLoad) NativeVpnStep(result = SessionChecker.Result("valid", "verified", vpnAuthState = "valid"))
                else step(if (libraryAtLoad) NativeVpnForm.Kind.LIBRARY else NativeVpnForm.Kind.SSO)
            }
            override fun validate(form: NativeVpnForm, guard: () -> Boolean) =
                !closed && formCurrent && active === form && !form.consumed.get() && time - form.createdAt <= 120_000 && guard()
            override fun submit(form: NativeVpnForm, username: String, password: String, captcha: String,
                remember: Boolean, guard: () -> Boolean): NativeVpnStep {
                beforeGuard()
                if (!validate(form, guard)) throw NativeVpnFailure("cancelled", kind = NativeVpnFailureKind.CANCELLED)
                check(form.consumed.compareAndSet(false, true))
                val kind = if (form.kind == NativeVpnForm.Kind.SSO) Kind.SSO else Kind.LIBRARY
                posts.add(Post(kind, form, username, captcha))
                if (unknownPost) throw NativeVpnFailure("synthetic-secret cookie=hidden ticket=hidden", true,
                    NativeVpnFailureKind.UNKNOWN_RESULT)
                if (thrownKind != NativeVpnFailureKind.NONE) throw NativeVpnFailure("synthetic-private", true, thrownKind)
                if (kind == Kind.SSO) afterSsoPost()
                if (kind == Kind.SSO && rejectCaptchas-- > 0) return step(NativeVpnForm.Kind.SSO, NativeVpnFailureKind.CAPTCHA_REJECTED)
                if (failureKind != NativeVpnFailureKind.NONE && (!failOnlyLibrary || kind == Kind.LIBRARY))
                    return step(form.kind, failureKind)
                return if (kind == Kind.SSO) step(NativeVpnForm.Kind.LIBRARY)
                else NativeVpnStep(result = SessionChecker.Result("valid", "verified", vpnAuthState = "valid"))
            }
            override fun cancel() { closed = true }
            override fun awaitQuiescence(timeoutMs: Long) = true
        }
        private val accounts = object : NativeRecoveryAccounts {
            override fun snapshot(kind: Kind): NativeRecoveryAccount? {
                accountReads++
                if (enabled[kind] != true || kind in paused) return null
                return NativeRecoveryAccount(revisions.getValue(kind),
                    if (kind == Kind.SSO) "synthetic-sso" else "synthetic-library", "synthetic-secret")
            }
            override fun isCurrent(kind: Kind, revision: String) = enabled[kind] == true && kind !in paused && revisions[kind] == revision
            override fun markVerified(kind: Kind, revision: String) {
                if (isCurrent(kind, revision)) { verified.add(kind); onVerified(kind) }
            }
            override fun pause(kind: Kind, revision: String) { if (revisions[kind] == revision) paused.add(kind) }
        }
        private val reader = object : NativeCaptchaReader {
            override fun recognize(image: ByteArray): DdddOcrEngine.Result {
                recognitions++; onRecognize()
                return if (results.isEmpty()) good() else results.removeFirst()
            }
            override fun close() { readerClosed = true; readerCloseCount++ }
        }
        val recovery = NativeWebVpnRecovery(session, accounts, reader, allowSso, true,
            { owner }, { statuses.add(it) }, { time }, { duration -> time += duration; slept += duration })
        override fun close() = recovery.close()
    }

    companion object {
        private fun good() = DdddOcrEngine.Result("1234", .99, "1234", 1)
        private fun bad() = DdddOcrEngine.Result("1234", .30, "1234", 1)
    }
}
