package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

@Composable
fun RunHistoryCard(history: List<RuntimeStore.RunRecord>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("最近运行记录", style = MaterialTheme.typography.titleMedium)
            Text("仅保留最近 3 次任务，超出后自动清除最早一次。", style = MaterialTheme.typography.bodySmall)
            if (history.isEmpty()) Text("暂无运行记录")
            history.asReversed().forEach { run ->
                var expanded by remember(run.id) { mutableStateOf(false) }
                HorizontalDivider()
                Text("${run.title} · ${RuntimeStore.format(run.startedAt)}", style = MaterialTheme.typography.labelLarge)
                Text(run.lines.lastOrNull().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                if (run.lines.size > 1) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起步骤" else "查看步骤（${run.lines.size}）") }
                    if (expanded) Text(run.lines.dropLast(1).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
