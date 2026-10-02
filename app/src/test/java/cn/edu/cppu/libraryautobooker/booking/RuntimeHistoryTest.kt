package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeHistoryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun fourthActionDeletesOnlyOldestActionIncludingItsSteps() {
        val runtime = RuntimeStore(app)
        (1..4).forEach {
            runtime.record("RUNNING", "task-$it-start", "run-$it", "任务 $it")
            runtime.record("DONE", "task-$it-result", "run-$it")
        }
        assertEquals(listOf("run-2", "run-3", "run-4"), runtime.history().map { it.id })
        assertEquals(2, runtime.history().last().lines.size)
        assertFalse(runtime.prefs.getString("events", "")!!.contains("task-1"))
        assertFalse(runtime.prefs.getString("history", "")!!.contains("run-1"))
        assertEquals("DONE", runtime.prefs.getString("task_state", ""))
        assertEquals("task-4-result", runtime.prefs.getString("task_detail", ""))
    }

    @Test fun manyStepsAndBothBatchesAreOneActionNotSeparateActions() {
        val runtime = RuntimeStore(app)
        repeat(60) { runtime.record("RUNNING", "step $it", "one", "全天预约") }
        runtime.record("DONE", "两笔预约完成", "one")
        assertEquals(1, runtime.history().size)
        assertEquals(50, runtime.history().single().lines.size)
        assertTrue(runtime.history().single().lines.last().contains("两笔预约完成"))
    }

    @Test fun scheduledTaskAndManualTaskKeepSeparateHistories() {
        val runtime = RuntimeStore(app)
        val scheduled = RuntimeStore.scheduledAction(1234L)
        runtime.record("SCHEDULED", "等待放号", scheduled, "定时预约")
        runtime.record("RUNNING", "手动开始", "manual", "立即演练")
        runtime.record("TRIGGERED", "定时触发", scheduled)
        runtime.record("DONE", "手动结束", "manual")
        runtime.record("DONE", "定时结束", scheduled)
        assertEquals(2, runtime.history().size)
        assertEquals(listOf(3, 2), runtime.history().map { it.lines.size })
        assertEquals("定时预约", runtime.history().first().title)
        assertFalse(runtime.history().first().lines.any { it.contains("手动") })
    }

    @Test fun loginChecksAndWakeTestsDoNotConsumeHistoryQuota() {
        val runtime = RuntimeStore(app)
        runtime.record("RUNNING", "预约开始", "one")
        repeat(10) { runtime.session("valid", "已登录"); runtime.test("DONE", "仅测试唤醒") }
        assertEquals(1, runtime.history().size)
        assertEquals(1, runtime.history().single().lines.size)
    }

    @Test fun migrationPreservesLegacyEvidenceThenAgesItOut() {
        val prefs = app.getSharedPreferences("runtime_status", 0)
        prefs.edit().putString("events", "old-start\nold-result").putString("task_state", "DONE").commit()
        val runtime = RuntimeStore(app)
        assertEquals("legacy", runtime.history().single().id)
        assertEquals(2, runtime.history().single().lines.size)
        (1..3).forEach { runtime.record("DONE", "new-$it", "new-$it") }
        assertFalse(runtime.prefs.getString("events", "")!!.contains("old-result"))
        assertEquals(3, RuntimeStore(app).history().size)
    }
}
