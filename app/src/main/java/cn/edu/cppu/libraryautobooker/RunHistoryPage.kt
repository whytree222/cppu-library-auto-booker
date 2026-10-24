package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

/** Read-only task history, separate from the reservation draft and permission settings. */
@Composable
fun RunHistoryPage(history: List<RuntimeStore.RunRecord>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("运行记录", style = MaterialTheme.typography.headlineSmall)
        Text("仅保留最近 3 次任务，超出后自动清除最早一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (history.isEmpty()) {
            AppCard {
                AppEmptyState(Icons.AutoMirrored.Filled.List, "暂无运行记录", "安排或执行一次预约后，结果会显示在这里。")
            }
        } else {
            RunHistoryCard(history)
        }
        Text("步骤记录不代表预约成功，请以学校返回的结果及学校预约记录为准。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}
