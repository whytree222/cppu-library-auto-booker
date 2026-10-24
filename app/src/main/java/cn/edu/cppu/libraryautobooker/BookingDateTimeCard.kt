package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.booking.FixedBookingPlan
import cn.edu.cppu.libraryautobooker.booking.ImmediateBookingPlan
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import java.time.format.DateTimeFormatter

private val estimateFormatter = DateTimeFormatter.ofPattern("M 月 d 日")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookingDateTimeCard(
    config: BookingConfig,
    rulesLoading: Boolean,
    rulesStatus: String,
    onRefreshRules: () -> Unit,
    onConfigChange: (BookingConfig) -> Unit,
    onMessage: (String) -> Unit
) {
    AppCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "使用日期与时间",
            subtitle = "${config.room.name} · " +
                if (config.room.timeMode == "free") "自选起止时间" else "固定时段",
            action = {
                TextButton(onClick = onRefreshRules, enabled = !rulesLoading,
                    modifier = Modifier.testTag("refresh-slots")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (rulesLoading) "正在更新…" else "更新时段",
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        )
        Text("使用日期", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { index, tomorrow ->
                SegmentedButton(
                    selected = config.reserveTomorrow == tomorrow,
                    onClick = { onConfigChange(config.copy(reserveTomorrow = tomorrow)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        activeBorderColor = MaterialTheme.colorScheme.primary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag(if (tomorrow) "date-tomorrow" else "date-today")
                ) { Text(if (tomorrow) "明天" else "今天") }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EstimateRow("直接预约预计使用日", ImmediateBookingPlan.targetDate(config).format(estimateFormatter))
                EstimateRow("00:58 定时预计使用日", FixedBookingPlan.targetDate(config).format(estimateFormatter))
                Text("两个入口分别按当前时间和计划启动日计算，请按需核对。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        if (config.room.timeMode == "free") {
            Text("按 24 小时制填写开始和结束时间，实际开放时长以学校系统为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.startTime,
                    onValueChange = { onConfigChange(config.copy(startTime = it, appendLastThree = false)) },
                    label = { Text("开始时间") },
                    placeholder = { Text("08:00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.endTime,
                    onValueChange = { onConfigChange(config.copy(endTime = it, appendLastThree = false)) },
                    label = { Text("结束时间") },
                    placeholder = { Text("12:00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            val limits = buildList {
                if (config.room.minMinutes > 0) add("最短 ${config.room.minMinutes} 分钟")
                if (config.room.maxMinutes > 0) add("最长 ${config.room.maxMinutes} 分钟")
                if (config.room.stepMinutes > 0) add("步长 ${config.room.stepMinutes} 分钟")
            }
            if (limits.isNotEmpty()) Text(limits.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val count = config.selectedSlots.size
            Text(if (count == 0) "选择 1–4 个连续时段" else "已选 $count 个连续时段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.room.slots.isEmpty()) {
                Text("尚未获取该阅览室的固定时段，请从学校同步阅览室。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                config.room.slots.forEachIndexed { index, slot ->
                    val selected = index in config.selectedSlots
                    FilterChip(
                        selected = selected,
                        enabled = !rulesLoading,
                        onClick = {
                            val next = (if (selected) config.selectedSlots - index else config.selectedSlots + index)
                                .distinct().sorted()
                            if (next.size > 4 || (next.isNotEmpty() && next.last() - next.first() + 1 != next.size)) {
                                onMessage("每笔只能选择 1–4 个连续时段；可先取消边缘时段再调整")
                            } else {
                                onConfigChange(config.copy(
                                    selectedSlots = next,
                                    appendLastThree = config.appendLastThree &&
                                        config.room.appendCompatible && next == listOf(0, 1, 2, 3)
                                ))
                            }
                        },
                        label = {
                            Text(
                                "${index + 1} · ${slot.begin.take(5)}–${slot.end.take(5)}",
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("slot-$index")
                    )
                }
            }
            if (config.room.appendCompatible && config.selectedSlots == listOf(0, 1, 2, 3)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("同一座位追加后三个时段", style = MaterialTheme.typography.titleSmall)
                        Text("前四段成功后追加第 5–7 段；后三段失败时保留前四段，共两笔预约。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = config.appendLastThree,
                        onCheckedChange = { onConfigChange(config.copy(appendLastThree = it)) },
                        modifier = Modifier.testTag("append-last-three")
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            ExpandableText(rulesStatus, collapsedMaxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EstimateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}
