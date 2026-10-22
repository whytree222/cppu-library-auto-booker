package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.LegacyFeatureMigration
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyFeatureMigrationTest {
    @Test fun upgradeCancelsOldTimesAndErasesOnlyRetiredAccountsWhilePreservingDirectDraft() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("booking_config", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("enabled", true).putLong("scheduled_at", 1000L)
            .putString("scheduled_times", "[1000,2000]").putString("release_times", "[60,90]")
            .putString("seat_numbers", "[\"G015A\"]").putString("selected_slots", "[0,1]")
            .putBoolean("reserve_tomorrow", false).putBoolean("webvpn_backup", true).commit()
        val direct = app.getSharedPreferences("auto_login", Context.MODE_PRIVATE)
        direct.edit().putString("encrypted", "synthetic-direct-ciphertext").commit()
        listOf("webvpn_sso_credentials_v1", "webvpn_library_credentials_v1", "webvpn_ocr").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().putString("encrypted", "synthetic-retired").commit()
        }
        RuntimeStore(app).prefs.edit().putString("active_route", "WEBVPN").putString("login_vpn_auth_state", "valid").commit()
        val loaded = ConfigStore(app).load()
        assertFalse(loaded.enabled); assertTrue(loaded.pendingTimes().isEmpty())
        assertEquals(listOf(58), loaded.times()); assertFalse(loaded.dryRun); assertTrue(loaded.waitForRelease)
        assertEquals(listOf("G015A"), loaded.seatNumbers); assertEquals(listOf(0, 1), loaded.selectedSlots)
        assertFalse(loaded.reserveTomorrow)
        assertEquals("synthetic-direct-ciphertext", direct.getString("encrypted", null))
        listOf("webvpn_sso_credentials_v1", "webvpn_library_credentials_v1", "webvpn_ocr").forEach {
            assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty())
        }
        assertEquals("DIRECT", RuntimeStore(app).prefs.getString("active_route", null))
        assertFalse(RuntimeStore(app).prefs.contains("login_vpn_auth_state"))
        // Running the migration again cannot cancel a newly authorized fixed-time task.
        val newlyPlanned = loaded.withPending(listOf(5000L))
        ConfigStore(app).save(newlyPlanned)
        LegacyFeatureMigration.run(app)
        assertEquals(listOf(5000L), ConfigStore(app).load().pendingTimes())
    }
}
