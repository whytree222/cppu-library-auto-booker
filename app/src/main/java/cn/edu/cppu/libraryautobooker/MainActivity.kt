package cn.edu.cppu.libraryautobooker

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.booking.BookingScheduler
import cn.edu.cppu.libraryautobooker.booking.BookingService
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.SeatCatalog
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var refreshVersion by mutableStateOf(0)
    private var loginEntryVisible by mutableStateOf(true)
    private var managingLogin by mutableStateOf(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        uiHandler.post { refreshVersion++ }
    }
    private val sessionProbe = object : Runnable {
        override fun run() {
            if (loginEntryVisible) return
            refreshVersion++
            SessionCoordinator.check(this@MainActivity) { valid, _ ->
                val state = RuntimeStore(this@MainActivity).prefs.getString("session_state", "unknown")
                if (!valid && state == "expired" && !isFinishing && !isDestroyed && !loginEntryVisible) {
                    managingLogin = false
                    loginEntryVisible = true
                }
            }
            uiHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        val store = ConfigStore(this)
        val current = store.load()
        val runtime = RuntimeStore(this)
        val now = System.currentTimeMillis()
        if (current.enabled && current.scheduledAtMillis > now && BookingScheduler(this).canScheduleExact()) {
            runCatching { BookingScheduler(this).scheduleAt(current.scheduledAtMillis) }
                .onFailure { runtime.record("FAILED", "重新登记闹钟失败，请检查权限并重新保存任务", RuntimeStore.scheduledAction(current.scheduledAtMillis)) }
        } else if (current.enabled && current.scheduledAtMillis > 0 && current.scheduledAtMillis < now - 30_000L) {
            if (runtime.prefs.getString("task_state", "") == "SCHEDULED") {
                runtime.record("DELAYED", "计划时间已过，尚未收到闹钟触发记录；请检查权限、省电与自启动设置", RuntimeStore.scheduledAction(current.scheduledAtMillis))
            }
        }
        if (runtime.prefs.getString("task_state", "") in setOf("RUNNING", "TRIGGERED") &&
            now - runtime.prefs.getLong("task_at", now) > 330_000L) {
            runtime.record("INTERRUPTED", "任务没有留下完成结果，可能已中断；请核对学校预约记录")
        }
        uiHandler.removeCallbacks(sessionProbe)
        if (!loginEntryVisible) uiHandler.post(sessionProbe)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(sessionProbe)
        super.onPause()
    }

    override fun onDestroy() {
        RuntimeStore(this).prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        getSharedPreferences("booking_config", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        val scheduler = BookingScheduler(this)
        val runtime = RuntimeStore(this)
        runtime.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val setupPrefs = getSharedPreferences("permission_setup", MODE_PRIVATE)
        getSharedPreferences("booking_config", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)

        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF176B4D))) {
                LaunchedEffect(loginEntryVisible) {
                    uiHandler.removeCallbacks(sessionProbe)
                    if (!loginEntryVisible) uiHandler.postDelayed(sessionProbe, 60_000L)
                }
                var showPermissionSetup by rememberSaveable { mutableStateOf(!setupPrefs.getBoolean("shown", false)) }
                if (showPermissionSetup && !loginEntryVisible) PermissionSetup {
                    setupPrefs.edit().putBoolean("shown", true).apply()
                    showPermissionSetup = false
                    refreshVersion++
                }
                var config by remember { mutableStateOf(store.load()) }
                var status by remember { mutableStateOf("尚未启用") }
                var pendingSchedule by remember { mutableStateOf<BookingConfig?>(null) }
                var savedEnabled by remember { mutableStateOf(config.enabled) }
                val liveConfig = remember(refreshVersion) { store.load() }
                LaunchedEffect(refreshVersion) {
                    if (liveConfig.enabled != savedEnabled) {
                        config = config.copy(enabled = liveConfig.enabled, scheduledAtMillis = liveConfig.scheduledAtMillis)
                        savedEnabled = liveConfig.enabled
                    }
                }
                val sessionDetail = remember(refreshVersion) { runtime.prefs.getString("session_detail", "登录状态待验证").orEmpty() }
                val sessionAt = remember(refreshVersion) { runtime.prefs.getLong("session_at", 0L) }
                val taskDetail = remember(refreshVersion) { runtime.prefs.getString("task_detail", "尚未安排任务").orEmpty() }
                val history = remember(refreshVersion) { runtime.history() }
                val testDetail = remember(refreshVersion) {
                    val state = runtime.prefs.getString("test_state", "")
                    val due = runtime.prefs.getLong("test_due", 0L)
                    val overdue = state == "SCHEDULED" && due > 0 && System.currentTimeMillis() - due > 10_000L
                    if (overdue) "测试时间已过，未收到闹钟触发记录"
                    else runtime.prefs.getString("test_detail", "尚未进行后台唤醒测试").orEmpty()
                }
                val arrange: (BookingConfig) -> Unit = { requested ->
                    runCatching { scheduler.schedule(requested) }.onSuccess { next ->
                        config = requested.copy(enabled = true, scheduledAtMillis = next.toInstant().toEpochMilli())
                        status = "已安排${if (requested.dryRun) "演练（不提交）" else "真实预约"}：${next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))}"
                    }.onFailure { status = "安排失败，请检查精确闹钟权限；详情见任务记录" }
                }
                val exactPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    val requested = pendingSchedule
                    pendingSchedule = null
                    if (requested != null) {
                        if (scheduler.canScheduleExact()) arrange(requested)
                        else status = "未获得精确闹钟权限，任务没有安排"
                    }
                    refreshVersion++
                }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                var seatInput by remember { mutableStateOf(config.seatNumbers.joinToString("\n")) }

                // Keep draft reservation settings alive while an expired session is restored.
                if (loginEntryVisible) {
                    EntryLoginScreen(autoEnterValid = !managingLogin) {
                        loginEntryVisible = false; managingLogin = false; refreshVersion++
                    }
                    return@MaterialTheme
                }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("图书馆自动预约", style = MaterialTheme.typography.headlineMedium)
                    Text("过刊阅览室 · 仅在校园网络下运行")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("登录状态", style = MaterialTheme.typography.titleMedium)
                            Text(sessionDetail)
                            Text("最近验证：${RuntimeStore.format(sessionAt)}；打开应用时及前台每分钟验证一次")
                            OutlinedButton(onClick = { SessionCoordinator.check(this@MainActivity) }) { Text("立即检查登录") }
                            val credentials = remember(refreshVersion) { CredentialStore(this@MainActivity) }
                            Text(if (!credentials.configured) "自动重新登录：未设置账号密码"
                                else if (credentials.paused) "自动重新登录：已暂停，请核对账号密码后重新登录"
                                else if (credentials.enabled) "自动重新登录：已开启" else "自动重新登录：已关闭")
                            OutlinedButton(onClick = { managingLogin = true; loginEntryVisible = true }) { Text("登录 / 管理账号") }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("定时任务与后台状态", style = MaterialTheme.typography.titleMedium)
                            Text(if (liveConfig.enabled) "计划：${RuntimeStore.format(liveConfig.scheduledAtMillis)} · ${if (liveConfig.dryRun) "演练，不提交" else "真实预约"}" else "当前没有等待触发的定时任务")
                            Text(taskDetail)
                            Text("精确闹钟：${if (scheduler.canScheduleExact()) "已允许" else "未允许"}；通知：${if (NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) "已允许" else "未允许"}")
                            val power = getSystemService(PowerManager::class.java)
                            Text("系统省电豁免：${if (power.isIgnoringBatteryOptimizations(packageName)) "已开启" else "未开启"}")
                            Text("小米等机型还需在系统应用设置中检查后台运行、自启动和省电限制；测试时保持校园网络连接。")
                            OutlinedButton(onClick = { showPermissionSetup = true }) { Text("重新查看权限引导") }
                            OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                            }) { Text("打开系统应用设置") }
                            OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }) { Text("查看系统省电设置") }
                            OutlinedButton(onClick = {
                                if (!scheduler.canScheduleExact()) {
                                    status = "请允许精确闹钟，然后再次点击测试"
                                    exactPermission.launch(scheduler.exactAlarmSettingsIntent())
                                } else {
                                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    runCatching { scheduler.scheduleWakeTest() }
                                        .onSuccess { status = "60 秒后测试后台唤醒，请锁屏等待；不会预约，也不改变正式任务" }
                                        .onFailure { status = it.message ?: "安排测试失败，请检查系统权限" }
                                }
                            }) { Text("60 秒后台唤醒测试（不预约）") }
                            Text("请避开放号前 20 分钟测试，以免连续闹钟受到系统频率限制。", style = MaterialTheme.typography.bodySmall)
                            Text(testDetail)
                        }
                    }
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

                    ReleaseTimeField(config.releaseHour, config.releaseMinute) { hour, minute ->
                        config = config.copy(releaseHour = hour, releaseMinute = minute)
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("按优先顺序填写座位号", style = MaterialTheme.typography.titleMedium)
                            Text("每行一个，第一行最优先。放号时按顺序尝试，最多 10 个。")
                            Text("过刊阅览室共 94 个位置：G001–G023 每桌 A/B/C/D，以及 YXS1、YXS2。")
                            OutlinedTextField(
                                value = seatInput,
                                onValueChange = { seatInput = it },
                                label = { Text("座位号") },
                                placeholder = { Text("G023D\nG015A\nYXS1") },
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
                                        config = config.copy(selectedSlots = next,
                                            appendLastThree = config.appendLastThree && next == listOf(0, 1, 2, 3))
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
                            if (config.selectedSlots == listOf(0, 1, 2, 3)) {
                                SettingSwitch(
                                    title = "同一座位追加后三个时段",
                                    detail = "前四段成功后，自动预约该座位的第 5–7 段。分两笔提交；后三段失败时保留前四段。",
                                    checked = config.appendLastThree,
                                    onChecked = { config = config.copy(appendLastThree = it) }
                                )
                            }
                        }
                    }
                    SettingSwitch(
                        title = "演练模式",
                        detail = if (config.appendLastThree) "只演练前四段；不提交预约，也不启动后三段" else "会走到座位图并识别座位，但不会提交预约",
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
                            val seatError = SeatCatalog.error(seatNumbers)
                            if (config.enabled && seatError != null) {
                                status = seatError
                                return@Button
                            }
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
                                    pendingSchedule = config
                                    exactPermission.launch(scheduler.exactAlarmSettingsIntent())
                                    status = "请允许“闹钟和提醒”；返回应用后会继续安排任务"
                                } else {
                                    arrange(config)
                                }
                            } else {
                                val cancelledAt = store.load().scheduledAtMillis
                                scheduler.cancel()
                                config = config.copy(scheduledAtMillis = 0L)
                                store.save(config)
                                if (cancelledAt > 0) runtime.record("CANCELLED", "定时任务已取消", RuntimeStore.scheduledAction(cancelledAt))
                                status = "自动运行已关闭"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存并安排任务") }
                    OutlinedButton(
                        onClick = {
                            val seatNumbers = parseSeatNumbers(seatInput)
                            val seatError = SeatCatalog.error(seatNumbers)
                            if (seatError != null) {
                                status = seatError
                                return@OutlinedButton
                            }
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
                            runCatching { ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, BookingService::class.java).apply {
                                    action = BookingService.ACTION_RUN_ONCE
                                }
                            ) }.onSuccess {
                                status = if (config.dryRun) "已开始立即演练" else "已开始单次预约"
                            }.onFailure {
                                status = "启动预约服务失败：${it.javaClass.simpleName}"
                                runtime.record("FAILED", status, "manual-failed:${java.util.UUID.randomUUID()}", "立即任务")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (config.dryRun) "立即演练" else "立即运行一次") }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    RunHistoryCard(history)
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
