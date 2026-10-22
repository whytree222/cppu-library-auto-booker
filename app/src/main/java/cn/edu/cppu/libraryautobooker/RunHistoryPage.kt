package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("运行记录", style = MaterialTheme.typography.headlineSmall)
        Text("查看每次任务的最终结果，展开后可核对闹钟、登录检查及预约步骤。",
            style = MaterialTheme.typography.bodyMedium)
        RunHistoryCard(history)
        if (history.isEmpty()) {
            Text("完成一次演练或安排预约后，任务记录会显示在这里。",
                style = MaterialTheme.typography.bodyMedium)
        }
        Text("记录中的“开始”“已选座”等步骤不代表预约成功，请以学校返回的结果及学校预约记录为准。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}
