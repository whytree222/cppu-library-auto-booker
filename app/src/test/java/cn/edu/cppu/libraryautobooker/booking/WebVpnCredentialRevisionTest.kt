package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebVpnCredentialRevisionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32) { (it + 5).toByte() }, "AES")
    private fun account(kind: Kind) = WebVpnCredentialStore(app, kind, { key })

    @Before fun clear() {
        Kind.entries.forEach { app.getSharedPreferences(it.storageName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun lateResponsesFromAnotherInstanceCannotChangeNewCredentials() {
        val old = account(Kind.SSO)
        old.save("synthetic-old", "synthetic-old-password")
        val previousRevision = old.revision
        val current = account(Kind.SSO)
        current.save("synthetic-new", "synthetic-new-password")
        assertFalse(old.markVerifiedIfRevision(previousRevision))
        assertFalse(old.pauseIfRevision(previousRevision))
        assertFalse(current.verified)
        assertFalse(current.paused)
        assertEquals("synthetic-new", current.read()?.username)
        assertTrue(old.markVerifiedIfRevision(current.revision))
        assertTrue(current.verified)
        assertTrue(old.pauseIfRevision(current.revision))
        assertTrue(current.paused)
        assertFalse(current.verified)
        assertFalse(old.markVerifiedIfRevision(current.revision))
    }

    @Test fun disabledOrDeletedCredentialsAreNotVerifiedOrPausedByOldCompletion() {
        val store = account(Kind.LIBRARY)
        store.save("synthetic-reader", "synthetic-password")
        val revision = store.revision
        store.setEnabled(false)
        assertFalse(account(Kind.LIBRARY).markVerifiedIfRevision(revision))
        assertFalse(account(Kind.LIBRARY).pauseIfRevision(revision))
        store.clear()
        assertFalse(account(Kind.LIBRARY).markVerifiedIfRevision(revision))
        assertFalse(account(Kind.LIBRARY).pauseIfRevision(revision))
        assertFalse(store.configured)
        assertFalse(store.paused)
    }

    @Test fun saveAndLatePauseAreSerializedAcrossInstancesOfTheSameKind() {
        val previous = account(Kind.SSO)
        previous.save("synthetic-old", "synthetic-old-password")
        val oldRevision = previous.revision
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptedPause = CountDownLatch(1)
        val finishedPause = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val error = AtomicReference<Throwable>()
        val saving = WebVpnCredentialStore(app, Kind.SSO, {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            key
        })
        val saveThread = Thread {
            try { saving.save("synthetic-new", "synthetic-new-password") } catch (failure: Throwable) { error.set(failure) }
        }
        val pauseThread = Thread {
            try {
                attemptedPause.countDown()
                result.set(account(Kind.SSO).pauseIfRevision(oldRevision))
            } catch (failure: Throwable) { error.set(failure) }
            finally { finishedPause.countDown() }
        }
        try {
            saveThread.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            pauseThread.start()
            assertTrue(attemptedPause.await(3, TimeUnit.SECONDS))
            assertFalse("late pause must wait for the other instance's save", finishedPause.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            saveThread.join(3000)
            if (pauseThread.state != Thread.State.NEW) pauseThread.join(3000)
        }
        assertNull(error.get())
        assertFalse(saveThread.isAlive)
        assertFalse(pauseThread.isAlive)
        assertEquals(false, result.get())
        assertFalse(previous.paused)
        assertEquals("synthetic-new", previous.read()?.username)
    }
}
