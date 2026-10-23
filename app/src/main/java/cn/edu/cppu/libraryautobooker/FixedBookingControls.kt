package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FixedBookingInfo() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("预约启动：下次 00:58", style = MaterialTheme.typography.titleMedium)
            Text("按手机本地时间安排一次真实预约；00:58 已过则到次日。任务启动后，明确尚未开放时最多等待 10 分钟，开放后按候选顺序选座。")
            Text("网络、登录、参数错误或学校其他业务拒绝会停止；提交结果不明不重发。不会每天自动重复。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun FixedBookingButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("预约 · 下次 00:58 启动") }
}

@Composable
fun ImmediateBookingButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("直接预约") }
    Text("立即按当前日期和所选使用时段尝试真实预约；不等到 00:58，也不等待开放十分钟。不改动已安排的定时计划。",
        style = MaterialTheme.typography.bodySmall)
}
