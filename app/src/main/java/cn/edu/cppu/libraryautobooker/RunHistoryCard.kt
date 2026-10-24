package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

private fun historyState(state: String): Pair<String, StatusTone> = when (state) {
    "DONE" -> "已完成" to StatusTone.Success
    "FAILED" -> "失败" to StatusTone.Error
    "RUNNING", "TRIGGERED" -> "运行中" to StatusTone.Info
    "SCHEDULED" -> "已安排" to StatusTone.Info
    "CANCELLED" -> "已取消" to StatusTone.Neutral
    "DELAYED" -> "已延迟" to StatusTone.Warning
    "INTERRUPTED" -> "需核对" to StatusTone.Warning
    else -> "已记录" to StatusTone.Neutral
}

@Composable
fun RunHistoryCard(history: List<RuntimeStore.RunRecord>) {
    history.asReversed().forEach { run ->
        key(run.id) {
            var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
            val (label, tone) = historyState(run.state)
            AppCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(run.title, style = MaterialTheme.typography.titleMedium)
                        Text(formatRecordTime(run.startedAt), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(label, tone)
                }
                Text(
                    run.lines.lastOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (run.state == "FAILED") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                if (run.lines.size > 1) {
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.Start)) {
                        Text(if (expanded) "收起步骤" else "查看步骤（${run.lines.size}）")
                    }
                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            SelectionContainer {
                                Text(
                                    run.lines.dropLast(1).joinToString("\n"),
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
