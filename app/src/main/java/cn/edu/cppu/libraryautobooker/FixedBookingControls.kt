package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BookingActionsCard(
    scheduled: Boolean,
    running: Boolean,
    directEnabled: Boolean,
    scheduledEnabled: Boolean,
    directPending: Boolean = false,
    status: String,
    onDirect: () -> Unit,
    onSchedule: () -> Unit,
    onCancelSchedule: () -> Unit,
    onStop: () -> Unit
) {
    if (status.isNotBlank()) StatusBanner(status)
    AppCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("开始预约", "定时任务为一次性，不会每天自动重复")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onSchedule,
                enabled = scheduledEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("预约 · 下次 00:58 启动", modifier = Modifier.padding(start = 8.dp))
            }
            Text("按手机本地时间启动，最多等待开放 10 分钟；00:58 已过则顺延到次日。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = onDirect,
                enabled = directEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                if (directPending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = androidx.compose.material3.LocalContentColor.current)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(if (directPending) "等待预约结果…" else "直接预约",
                    modifier = Modifier.padding(start = 8.dp))
            }
            Text("现在按当前日期和所选时段尝试，不等待开放，也不改动已安排的定时任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (scheduled) {
            OutlinedButton(onClick = onCancelSchedule, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("取消已安排预约", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (running) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("停止当前任务", modifier = Modifier.padding(start = 8.dp))
            }
        }
        ExpandableText(
            "提交结果不明时应用会停止，不会盲目重发；请以学校预约记录为准。需保持校园网可用，图书馆站点使用 HTTP。",
            collapsedMaxLines = 2
        )
    }
}

@Composable
private fun StatusBanner(status: String) {
    val error = listOf("失败", "无法", "错误", "异常", "不存在", "缺少", "不能", "不可", "未启动").any { it in status }
    val attention = !error && listOf("请允许", "请先", "请等待", "已有", "尚未", "未获得", "请检查", "需核对", "暂不")
        .any { it in status }
    val container: Color = when {
        error -> MaterialTheme.colorScheme.errorContainer
        attention -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content: Color = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        attention -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top) {
            Icon(
                if (error || attention) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            ExpandableText(status, style = MaterialTheme.typography.bodySmall, color = content,
                collapsedMaxLines = 3, modifier = Modifier.weight(1f))
        }
    }
}
