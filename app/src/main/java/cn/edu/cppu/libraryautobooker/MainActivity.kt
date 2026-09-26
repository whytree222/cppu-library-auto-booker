package cn.edu.cppu.libraryautobooker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.booking.BookingScheduler
import cn.edu.cppu.libraryautobooker.booking.BookingService
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import androidx.core.content.ContextCompat
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        val scheduler = BookingScheduler(this)

        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF176B4D))) {
                var config by remember { mutableStateOf(store.load()) }
                var status by remember { mutableStateOf("尚未启用") }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                val seatPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { config = store.load() }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("图书馆自动预约", style = MaterialTheme.typography.headlineMedium)
                    Text("仅在连接校内网络时工作；账号登录由学校网页完成。")
                    Text(
                        "安全提醒：校方系统目前使用 HTTP，登录信息没有 TLS 传输保护。请只在可信的校园局域网中登录。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("准备", style = MaterialTheme.typography.titleMedium)
                    Text("1. 连接校园局域网并登录\n2. 前一晚依次选好候选位置\n3. 设置放号时间，先演练一次")
                            OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, LoginActivity::class.java)) }) {
                                Text("打开预约系统 / 登录")
                            }
                        }
                    }

                    TimeField("每天放号时间", config.releaseHour, config.releaseMinute) { hour, minute ->
                        config = config.copy(releaseHour = hour, releaseMinute = minute)
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("候选座位", style = MaterialTheme.typography.titleMedium)
                            Text("在座位图上依次点选，先点的优先；最多 10 个。灰色位置也可以提前记录，放号时会检查是否变为绿色。")
                            if (config.seatChoices.isEmpty()) Text("尚未选择位置")
                            config.seatChoices.forEachIndexed { index, seat ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${seat.label}", Modifier.weight(1f))
                                    OutlinedButton(onClick = {
                                        config = config.copy(seatChoices = config.seatChoices.filterIndexed { i, _ -> i != index })
                                        store.save(config)
                                    }) { Text("移除") }
                                }
                            }
                            OutlinedButton(onClick = {
                                store.save(config)
                                seatPicker.launch(Intent(this@MainActivity, SeatPickerActivity::class.java))
                            }) { Text("在一楼座位图中选择") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = config.startTime,
                            onValueChange = { config = config.copy(startTime = it) },
                            label = { Text("开始") }, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.endTime,
                            onValueChange = { config = config.copy(endTime = it) },
                            label = { Text("结束") }, modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = config.entryPath,
                        onValueChange = { config = config.copy(entryPath = it) },
                        label = { Text("预约入口路径（高级）") },
                        supportingText = { Text("一楼预约列表入口：/multireadingroomtablelist") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    SettingSwitch(
                        title = "预约次日座位",
                        detail = "关闭后预约当天；默认在放号时预约次日",
                        checked = config.reserveTomorrow,
                        onChecked = { config = config.copy(reserveTomorrow = it) }
                    )
                    SettingSwitch(
                        title = "演练模式",
                        detail = "开启时只定位按钮，不会提交预约",
                        checked = config.dryRun,
                        onChecked = { config = config.copy(dryRun = it) }
                    )
                    SettingSwitch(
                        title = "下次放号时运行一次",
                        detail = "需要精确闹钟权限；运行后自动关闭",
                        checked = config.enabled,
                        onChecked = { config = config.copy(enabled = it) }
                    )

                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            if (config.enabled) {
                                if (config.seatChoices.isEmpty()) {
                                    config = config.copy(enabled = false, scheduledAtMillis = 0L)
                                    store.save(config)
                                    status = "请先选择至少一个候选座位"
                                } else if (!scheduler.canScheduleExact()) {
                                    config = config.copy(enabled = false, scheduledAtMillis = 0L)
                                    store.save(config)
                                    startActivity(scheduler.exactAlarmSettingsIntent())
                                    status = "请允许“闹钟和提醒”，然后再次保存"
                                } else {
                                    val next = scheduler.schedule(config)
                                    config = config.copy(scheduledAtMillis = next.toInstant().toEpochMilli())
                                    store.save(config)
                                    status = "已安排：${next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}"
                                }
                            } else {
                                scheduler.cancel()
                                config = config.copy(scheduledAtMillis = 0L)
                                store.save(config)
                                status = "自动运行已关闭"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存并安排任务") }
                    OutlinedButton(
                        onClick = {
                            if (config.seatChoices.isEmpty()) {
                                status = "请先选择至少一个候选座位"
                                return@OutlinedButton
                            }
                            store.save(config)
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, BookingService::class.java).apply {
                                    action = BookingService.ACTION_RUN_ONCE
                                }
                            )
                            status = if (config.dryRun) "已开始立即演练，请查看通知" else "已开始单次预约，请查看通知"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (config.dryRun) "立即演练" else "立即运行一次") }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "请先演练。若学校页面变化，应用会提示失败，请重新在座位图录入候选位置。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@androidx.compose.runtime.Composable
private fun TimeField(label: String, hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    var text by remember(hour, minute) { mutableStateOf("%02d:%02d".format(hour, minute)) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            val match = Regex("^(\\d{1,2}):(\\d{1,2})$").matchEntire(newText) ?: return@OutlinedTextField
            val h = match.groupValues[1].toIntOrNull() ?: return@OutlinedTextField
            val m = match.groupValues[2].toIntOrNull() ?: return@OutlinedTextField
            if (h in 0..23 && m in 0..59) onChange(h, m)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}
