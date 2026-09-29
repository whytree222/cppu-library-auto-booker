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
                var seatInput by remember { mutableStateOf(config.seatNumbers.joinToString("\n")) }

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
                            Text("1. 连接校园局域网并登录\n2. 每行输入一个座位号，越靠前优先级越高\n3. 设置放号时间，先演练一次")
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
                            Text("按优先顺序填写座位号", style = MaterialTheme.typography.titleMedium)
                            Text("每行一个，第一行最优先。放号时按顺序尝试，最多 10 个。")
                            OutlinedTextField(
                                value = seatInput,
                                onValueChange = { seatInput = it },
                                label = { Text("座位号") },
                                placeholder = { Text("G015A\nG016A\nG017A") },
                                minLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("当前顺序：${parseSeatNumbers(seatInput).joinToString(" → ").ifEmpty { "尚未填写" }}")
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("预约哪一天", style = MaterialTheme.typography.titleMedium)
                            Text("以任务运行当天为准。")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (config.reserveTomorrow) {
                                    OutlinedButton(
                                        onClick = { config = config.copy(reserveTomorrow = false) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("今天") }
                                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("✓ 明天") }
                                } else {
                                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("✓ 今天") }
                                    OutlinedButton(
                                        onClick = { config = config.copy(reserveTomorrow = true) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("明天") }
                                }
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("选择使用时段", style = MaterialTheme.typography.titleMedium)
                            Text("选择 1–4 个连续时段；下列时间为便于识别的约数，实际秒数以学校网页为准。")
                            slotLabels.forEachIndexed { index, label ->
                                val selected = index in config.selectedSlots
                                val onClick = {
                                    val next = (if (selected) config.selectedSlots - index else config.selectedSlots + index)
                                        .distinct().sorted()
                                    if (next.size > 4 || (next.isNotEmpty() && next.last() - next.first() + 1 != next.size)) {
                                        status = "每笔只能选择 1–4 个连续时段；可先取消边缘时段再调整"
                                    } else {
                                        config = config.copy(selectedSlots = next)
                                    }
                                }
                                if (selected) {
                                    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                                        Text("✓ ${index + 1}. $label")
                                    }
                                } else {
                                    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                                        Text("${index + 1}. $label")
                                    }
                                }
                            }
                        }
                    }
                    SettingSwitch(
                        title = "演练模式",
                        detail = "会走到座位图并识别座位，但不会提交预约",
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
                            val seatNumbers = parseSeatNumbers(seatInput)
                            config = config.copy(seatNumbers = seatNumbers)
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            if (config.enabled) {
                                if (seatNumbers.isEmpty() || seatNumbers.size > 10) {
                                    config = config.copy(enabled = false, scheduledAtMillis = 0L)
                                    store.save(config)
                                    status = "请输入 1 到 10 个座位号，每行一个"
                                } else if (config.selectedSlots.isEmpty()) {
                                    config = config.copy(enabled = false, scheduledAtMillis = 0L)
                                    store.save(config)
                                    status = "请选择 1–4 个连续使用时段"
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
                            val seatNumbers = parseSeatNumbers(seatInput)
                            if (seatNumbers.isEmpty() || seatNumbers.size > 10) {
                                status = "请输入 1 到 10 个座位号，每行一个"
                                return@OutlinedButton
                            }
                            if (config.selectedSlots.isEmpty()) {
                                status = "请选择 1–4 个连续使用时段"
                                return@OutlinedButton
                            }
                            config = config.copy(seatNumbers = seatNumbers)
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
                        "请先演练。若页面无法识别座位号，应用会停止，不会猜测或点击其他座位。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

private fun parseSeatNumbers(input: String): List<String> = input.lines()
    .map { it.trim().uppercase() }
    .filter { it.isNotEmpty() }
    .distinct()

private val slotLabels = listOf(
    "08:10–10:00", "10:01–11:29", "11:31–14:29", "14:31–16:30",
    "16:31–18:00", "18:01–19:29", "19:31–22:01"
)

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
