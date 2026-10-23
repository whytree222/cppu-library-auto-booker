package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import cn.edu.cppu.libraryautobooker.DirectBookingFeedback
import cn.edu.cppu.libraryautobooker.DirectBookingFeedbackDialog
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DirectBookingFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val id = "manual:00000000-0000-0000-0000-000000000001"
    private fun record(state: String, detail: String) = RuntimeStore.RunRecord(id,"立即预约",1L,state,listOf(detail))

    @Test fun progressAndScheduledResultsNeverBecomeDirectResultDialogs() {
        assertNull(DirectBookingFeedback.from(record("RUNNING","前四段预约成功；正在继续后三段")))
        assertNull(DirectBookingFeedback.from(record("DONE","预约成功").copy(id="scheduled:123")))
        assertNull(DirectBookingFeedback.from(record("SUBMITTED","已尝试提交")))
    }

    @Test fun terminalResultsPreserveActualSchoolReasonAndNeverInferSuccessFromText() {
        assertEquals("直接预约已完成",DirectBookingFeedback.from(record("DONE","G015A 预约成功"))!!.title)
        val failed = DirectBookingFeedback.from(record("FAILED","学校提示：座位已被预约"))!!
        assertEquals("直接预约未完成",failed.title)
        assertEquals("学校提示：座位已被预约",failed.detail)
        val partial=DirectBookingFeedback.from(record("FAILED","前四段已成功，后三段被学校拒绝"))!!
        assertEquals("直接预约未完成",partial.title)
        assertTrue(partial.detail.contains("前四段已成功"))
    }

    @Test fun ambiguousOrInterruptedResultRequiresCheckingSchoolRecords() {
        assertEquals("预约结果待核对",DirectBookingFeedback.from(record("FAILED","点击预约后45秒未收到明确结果，请核对学校记录"))!!.title)
        assertEquals("预约结果待核对",DirectBookingFeedback.from(record("INTERRUPTED","服务提前结束"))!!.title)
    }

    @Test fun requestIdsAreStrictlyBoundAndCannotMasqueradeAsScheduledRuns() {
        assertEquals(id,ImmediateBookingPlan.actionId(Intent().putExtra(ImmediateBookingPlan.EXTRA_ACTION_ID,id)))
        listOf("scheduled:123","manual:bad","").forEach {
            assertNull(ImmediateBookingPlan.actionId(Intent().putExtra(ImmediateBookingPlan.EXTRA_ACTION_ID,it)))
        }
    }

    @Test fun earlyServiceRejectionReportsUnderTheSameManualRequestId() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("runtime_status",Context.MODE_PRIVATE).edit().clear().commit()
        val controller=Robolectric.buildService(BookingService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(app,BookingService::class.java)
                .setAction(BookingService.ACTION_RUN_ONCE)
                .putExtra(ImmediateBookingPlan.EXTRA_ACTION_ID,id),0,1)
            val run=RuntimeStore(app).history().single()
            assertEquals(id,run.id)
            assertEquals("FAILED",run.state)
            assertNotNull(DirectBookingFeedback.from(run))
        } finally { controller.destroy() }
    }

    @Test fun popupShowsFullReasonAndCloseOnlyDismisses() {
        var closed=0
        compose.setContent { MaterialTheme {
            DirectBookingFeedbackDialog("直接预约未完成","二楼 · 报刊阅览区：座位已被预约") { closed++ }
        } }
        compose.onNodeWithText("直接预约未完成").assertIsDisplayed()
        compose.onNodeWithText("二楼 · 报刊阅览区：座位已被预约").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        assertEquals(1,closed)
    }
}
