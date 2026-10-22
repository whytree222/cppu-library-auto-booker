package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A settings-only page. Opening it never requests a permission or starts a test. */
@Composable
fun PermissionsPage(
    exact: Boolean,
    notifications: Boolean,
    battery: Boolean,
    testDetail: String,
    message: String = "",
    onExactAlarm: () -> Unit,
    onNotifications: () -> Unit,
    onAppSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onPermissionGuide: () -> Unit,
    onWakeTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().testTag("permissions-page")
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("权限设置", style = MaterialTheme.typography.headlineSmall)
        Text("定时预约需要的系统授权和后台运行检查都在这里。返回预约页可继续编辑原来的预约计划。",
            style = MaterialTheme.typography.bodyMedium)

        PermissionStatusCard(exact, notifications, battery) {}

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("系统授权与设置", style = MaterialTheme.typography.titleMedium)
                Text("点击对应按钮后，再由你决定是否在系统中开启。返回应用时会重新读取权限状态。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onExactAlarm, modifier = Modifier.fillMaxWidth()) {
                    Text("设置闹钟和提醒")
                }
                OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text("通知授权 / 设置")
                }
                OutlinedButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("打开系统应用设置")
                }
                OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("查看系统省电设置")
                }
                Text("小米手机请在系统中核对自启动和“无限制”省电策略。应用无法替你确认这些厂商开关。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onPermissionGuide, modifier = Modifier.fillMaxWidth()) {
                    Text("重新查看权限引导")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("后台唤醒测试", style = MaterialTheme.typography.titleMedium)
                Text("测试只验证锁屏后的闹钟与后台服务，不提交座位预约，也不更改正式定时任务。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onWakeTest, modifier = Modifier.fillMaxWidth()) {
                    Text("60 秒后台唤醒测试（不预约）")
                }
                Text("请避开放号前 20 分钟测试，以免连续闹钟受到系统频率限制。",
                    style = MaterialTheme.typography.bodySmall)
                Text(testDetail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
