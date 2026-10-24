package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import java.time.LocalTime

@Composable
fun TaskSummaryCard(
    liveConfig: BookingConfig,
    taskState: String,
    taskDetail: String,
    running: Boolean
) {
    val next = remember(liveConfig) { liveConfig.pendingTimes().firstOrNull() }
    val active = running || taskState in setOf("RUNNING", "TRIGGERED")
    val (label, tone) = when {
        active -> "正在执行" to StatusTone.Info
        next != null -> "已安排" to StatusTone.Success
        taskState == "DONE" -> "已完成" to StatusTone.Success
        taskState == "FAILED" -> "失败" to StatusTone.Error
        taskState in setOf("DELAYED", "INTERRUPTED") -> "需核对" to StatusTone.Warning
        taskState == "CANCELLED" -> "已取消" to StatusTone.Neutral
        else -> "未安排" to StatusTone.Neutral
    }
    val fontScale = LocalDensity.current.fontScale
    AppCard(shape = MaterialTheme.shapes.large, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(if (fontScale >= 1.3f) 8.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Filled.DateRange)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("预约任务", style = MaterialTheme.typography.titleMedium)
                    Text("${liveConfig.room.groupName} · ${liveConfig.room.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (fontScale < 1.3f) StatusPill(label, tone)
            }
            if (fontScale >= 1.3f) StatusPill(label, tone, Modifier.align(Alignment.End))
        }
        if (next != null && !active) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("计划启动", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatNextExecution(next), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "已选 ${liveConfig.seatNumbers.size} 个候选座位 · " +
                        if (liveConfig.reserveTomorrow) "使用明天时段" else "使用今天时段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(when {
                active -> "正在按候选顺序尝试当前任务"
                taskState == "DONE" -> "最近一次任务已完成"
                taskState == "FAILED" -> "最近一次任务未成功"
                taskState in setOf("DELAYED", "INTERRUPTED") -> "任务结果需要核对学校记录"
                taskState == "CANCELLED" -> "已取消等待中的任务"
                else -> "尚未安排下一次任务"
            }, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    next != null -> "任务已排队，开始执行后结果会更新在这里。"
                    taskDetail.isBlank() || taskDetail == "尚未安排任务" ->
                        "完成下方准备后，可立即预约或安排下一次 00:58 任务。"
                    else -> taskDetail
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PreparationCard(
    loginState: String,
    config: BookingConfig,
    hasSeatMap: Boolean,
    scheduled: Boolean
) {
    val loginReady = loginState == "valid"
    val roomReady = config.room.validationError() == null && hasSeatMap
    val seatReady = config.seatNumbers.isNotEmpty()
    val timeReady = if (config.room.timeMode == "fixed") config.selectedSlots.isNotEmpty()
    else {
        val clock = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        clock.matches(config.startTime) && clock.matches(config.endTime) &&
            runCatching { LocalTime.parse(config.endTime) > LocalTime.parse(config.startTime) }.getOrDefault(false)
    }
    if (loginReady && roomReady && seatReady && timeReady && scheduled) {
        AppCard(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChecklistRow(true, "准备就绪", "可直接预约，或安排下一次 00:58 任务")
        }
        return
    }
    val pending = buildList {
        if (!loginReady) add("检查登录" to if (loginState == "expired") "登录已过期，请先重新登录" else "确认校园网与登录状态")
        if (!roomReady) add("选择阅览室" to if (!hasSeatMap) "当前阅览室缺少已核对的座位图" else "选择楼层与阅览室")
        if (!seatReady) add("选择座位" to "在座位图中点击候选座位")
        if (!timeReady) add("选择使用时段" to "选择日期和连续时段，或填写起止时间")
        if (!scheduled) add("安排任务" to "直接预约现在尝试，或安排下一次 00:58 任务")
    }
    AppCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("预约准备", "还差 ${pending.size} 项即可开始")
        pending.forEach { (title, detail) -> ChecklistRow(null, title, detail) }
    }
}
