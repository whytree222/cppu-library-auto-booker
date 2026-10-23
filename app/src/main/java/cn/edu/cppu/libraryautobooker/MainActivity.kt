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
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import cn.edu.cppu.libraryautobooker.data.RoomCatalogStore
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import cn.edu.cppu.libraryautobooker.data.SeatMapCatalog
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import androidx.core.app.NotificationManagerCompat
import cn.edu.cppu.libraryautobooker.booking.FixedBookingPlan
import cn.edu.cppu.libraryautobooker.booking.ImmediateBookingPlan
import androidx.core.content.ContextCompat
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var refreshVersion by mutableStateOf(0)
    private var loginEntryVisible by mutableStateOf(true)
    private var managingLogin by mutableStateOf(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var foreground = false
    private val preferredSessionCheck = UiSessionRequest()
    private fun cancelSessionChecks() {
        preferredSessionCheck.cancel()
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        uiHandler.post { refreshVersion++ }
    }
    private val sessionProbe = object : Runnable {
        override fun run() {
            if (!foreground || loginEntryVisible) return
            refreshVersion++
            if (BookingService.running) {
                SessionChecker.probe(this@MainActivity, LibraryRoute.DIRECT, updateSession = false) {}
                uiHandler.postDelayed(this, 60_000L)
                return
            }
            if (!preferredSessionCheck.pending) {
                val ticket = preferredSessionCheck.begin()
                val handle = SessionCoordinator.check(this@MainActivity) { valid, _ ->
                    preferredSessionCheck.complete(ticket) {
                        val state = RuntimeStore(this@MainActivity).prefs.getString("session_state", "unknown")
                        if (foreground && !valid && state == "expired" && !isFinishing && !isDestroyed && !loginEntryVisible) {
                            managingLogin = false
                            loginEntryVisible = true
                        }
                    }
                }
                preferredSessionCheck.attach(ticket, handle)
            }
            uiHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refreshVersion++
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
        foreground = false
        uiHandler.removeCallbacks(sessionProbe)
        cancelSessionChecks()
        super.onPause()
    }

    override fun onDestroy() {
        foreground = false
        cancelSessionChecks()
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
                    if (loginEntryVisible) cancelSessionChecks()
                    else if (foreground) uiHandler.post(sessionProbe)
                }
                var showPermissionSetup by rememberSaveable { mutableStateOf(!setupPrefs.getBoolean("shown", false)) }
                if (showPermissionSetup && !loginEntryVisible) PermissionSetup {
                    setupPrefs.edit().putBoolean("shown", true).apply()
                    showPermissionSetup = false
                    refreshVersion++
                }
                var config by remember { mutableStateOf(store.load()) }
                var rooms by remember { mutableStateOf(RoomCatalogStore(this@MainActivity).load()) }
                val seatMaps = remember { runCatching { SeatMapCatalog.load(this@MainActivity) }.getOrNull() }
                var status by remember { mutableStateOf("尚未启用") }
                var permissionStatus by remember { mutableStateOf("") }
                var pendingSchedule by remember { mutableStateOf<BookingConfig?>(null) }
                var savedEnabled by remember { mutableStateOf(config.enabled) }
                val liveConfig = remember(refreshVersion) { store.load() }
                LaunchedEffect(refreshVersion) {
                    if (liveConfig.enabled != savedEnabled || liveConfig.scheduledAtMillis != config.scheduledAtMillis) {
                        config = config.copy(enabled = liveConfig.enabled, scheduledAtMillis = liveConfig.scheduledAtMillis,
                            scheduledTimes = liveConfig.scheduledTimes)
                        savedEnabled = liveConfig.enabled
                    }
                }
                val taskDetail = remember(refreshVersion) { runtime.prefs.getString("task_detail", "尚未安排任务").orEmpty() }
                val history = remember(refreshVersion) { runtime.history() }
                val exactAllowed = remember(refreshVersion) { scheduler.canScheduleExact() }
                val notificationsAllowed = remember(refreshVersion) {
                    NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()
                }
                val batteryAllowed = remember(refreshVersion) {
                    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                }
                val testDetail = remember(refreshVersion) {
                    val state = runtime.prefs.getString("test_state", "")
                    val due = runtime.prefs.getLong("test_due", 0L)
                    val overdue = state == "SCHEDULED" && due > 0 && System.currentTimeMillis() - due > 10_000L
                    if (overdue) "测试时间已过，未收到闹钟触发记录"
                    else runtime.prefs.getString("test_detail", "尚未进行后台唤醒测试").orEmpty()
                }
                val arrange: (BookingConfig) -> Unit = { requested ->
                    val map = seatMaps?.forRoom(requested.room)
                    val error = if (map == null) "此阅览室尚无已核对的座位图，请重新选择房间"
                        else map.selectionError(requested.seatNumbers) ?: requested.validationError()
                    if (error != null) status = error
                    else runCatching { scheduler.schedule(requested) }.onSuccess { next ->
                            config = store.load()
                            status = "已安排一次预约（最多等待开放 10 分钟）：${next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))}"
                        }.onFailure { status = "安排失败，请检查精确闹钟权限；详情见记录页" }
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
                ) { refreshVersion++ }
                val selectRoom: (RoomProfile) -> Unit = { room ->
                    if (room != config.room) {
                        val cancelledTimes = store.load().pendingTimes()
                        scheduler.cancel()
                        pendingSchedule = null
                        config = resetRoomDraft(config, room)
                        store.save(config)
                        cancelledTimes.forEach { at ->
                            runtime.record("CANCELLED", "阅览室已更改，原定时任务已取消，请重新确认座位和时段",
                                RuntimeStore.scheduledAction(at))
                        }
                        status = "已选择${room.name}；请在座位图选座、选择时间并保存任务"
                    }
                }
                val roomBrowser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    rooms = RoomCatalogStore(this@MainActivity).load()
                    val id = if (result.resultCode == RESULT_OK) result.data?.getStringExtra("room_id") ?: config.room.id
                        else config.room.id
                    rooms.firstOrNull { it.id == id }?.let(selectRoom)
                }

                // Keep draft reservation settings alive while an expired session is restored.
                if (loginEntryVisible) {
                    EntryLoginScreen(autoEnterValid = !managingLogin) {
                        loginEntryVisible = false; managingLogin = false; refreshVersion++
                    }
                    return@MaterialTheme
                }

                LibraryAppPages(booking = {
                  Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("预约设置", style = MaterialTheme.typography.headlineSmall)
                    Text("${config.room.groupName} · ${config.room.name} · 校园网直连", style = MaterialTheme.typography.bodyMedium)
                    LoginStatusCard(rememberLoginStatuses(), hasActions = true, onCheck = {
                        SessionChecker.probe(this@MainActivity, LibraryRoute.DIRECT, updateSession = false) {}
                    }) {
                            val credentials = remember(refreshVersion) { CredentialStore(this@MainActivity) }
                            Text(if (!credentials.configured) "自动重新登录：未设置账号密码"
                                else if (credentials.paused) "自动重新登录：已暂停，请核对账号密码后重新登录"
                                else if (credentials.enabled) "自动重新登录：已开启" else "自动重新登录：已关闭")
                            OutlinedButton(onClick = { managingLogin = true; loginEntryVisible = true }) { Text("登录 / 管理账号") }
                            OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, LoginActivity::class.java)) }) { Text("直连 · 图书馆网页登录") }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("预约计划与运行结果", style = MaterialTheme.typography.titleLarge)
                            Text(if (liveConfig.enabled) "已安排一次真实预约 · 最多等待开放 10 分钟" else "当前没有等待触发的预约计划")
                            if (liveConfig.enabled) liveConfig.pendingTimes().forEach { at ->
                                Text("计划：${RuntimeStore.format(at)}")
                            }
                            Text(taskDetail)
                        }
                    }
                    Text(
                        "安全提醒：图书馆使用 HTTP，请使用可信校园网；不再提供校外代理通道。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("准备", style = MaterialTheme.typography.titleMedium)
                            Text("1. 检查校园网和登录状态\n2. 选择楼层、阅览室和座位\n3. 选择使用日期和时段\n4. 直接预约现在尝试，或安排下次 00:58 启动、最多等待开放 10 分钟")
                        }
                    }

                    RoomSettingsCard(config.room, rooms, onSelect = selectRoom, onSync = {
                        roomBrowser.launch(Intent(this@MainActivity, RoomBrowserActivity::class.java))
                    })

                    SeatMapCard(seatMaps?.forRoom(config.room), config.seatNumbers) { selected ->
                        val draft = config.copy(seatNumbers = selected, seatChoices = emptyList(), mapSeats = emptyList())
                        val map = seatMaps?.forRoom(draft.room)
                        config = if (map != null && map.selectionError(selected) == null) map.bind(draft) else draft
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("预约哪一天", style = MaterialTheme.typography.titleMedium)
                            Text("直接预约预计使用日：${ImmediateBookingPlan.targetDate(config)}（今天/明天以现在为准）")
                            Text("00:58 定时预约预计使用日：${FixedBookingPlan.targetDate(config)}（今天/明天以计划启动日为准）")
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
                            Text("选择使用时间 · ${config.room.name}", style = MaterialTheme.typography.titleMedium)
                            if (config.room.timeMode == "free") {
                                Text("该阅览室按起止时间预约，请以 24 小时制填写 HH:mm；实际开放日期和时长限制以学校系统为准。")
                                if (config.room.minMinutes > 0) Text("最短 ${config.room.minMinutes} 分钟")
                                if (config.room.maxMinutes > 0) Text("最长 ${config.room.maxMinutes} 分钟")
                                if (config.room.stepMinutes > 0) Text("时间步长 ${config.room.stepMinutes} 分钟")
                                OutlinedTextField(value = config.startTime,
                                    onValueChange = { config = config.copy(startTime = it, appendLastThree = false) },
                                    label = { Text("开始时间（HH:mm）") }, placeholder = { Text("08:00") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = config.endTime,
                                    onValueChange = { config = config.copy(endTime = it, appendLastThree = false) },
                                    label = { Text("结束时间（HH:mm）") }, placeholder = { Text("12:00") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                            } else {
                                Text("选择 1–4 个连续时段；仅显示当前阅览室同步的时段，具体可预约状态会在运行时再次核对。")
                                if (config.room.slots.isEmpty()) {
                                    Text("尚未获取该阅览室的固定时段，请先从学校同步阅览室。", color = MaterialTheme.colorScheme.error)
                                }
                                config.room.slots.forEachIndexed { index, slot ->
                                    val selected = index in config.selectedSlots
                                    val onClick = {
                                        val next = (if (selected) config.selectedSlots - index else config.selectedSlots + index)
                                            .distinct().sorted()
                                        if (next.size > 4 || (next.isNotEmpty() && next.last() - next.first() + 1 != next.size)) {
                                            status = "每笔只能选择 1–4 个连续时段；可先取消边缘时段再调整"
                                        } else {
                                            config = config.copy(selectedSlots = next,
                                                appendLastThree = config.appendLastThree && config.room.appendCompatible && next == listOf(0, 1, 2, 3))
                                        }
                                    }
                                    if (selected) {
                                        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                                            Text("✓ ${index + 1}. ${slot.label}")
                                        }
                                    } else {
                                        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                                            Text("${index + 1}. ${slot.label}")
                                        }
                                    }
                                }
                                if (config.room.appendCompatible && config.selectedSlots == listOf(0, 1, 2, 3)) {
                                    SettingSwitch(
                                        title = "同一座位追加后三个时段",
                                        detail = "仅适用于已核对七个时段的过刊阅览室。前四段成功后追加第 5–7 段；后三段失败时保留前四段。",
                                        checked = config.appendLastThree,
                                        onChecked = { config = config.copy(appendLastThree = it) }
                                    )
                                }
                            }
                        }
                    }
                    ImmediateBookingButton(onClick = direct@{
                        if (BookingService.running) {
                            status = "已有预约任务正在运行，请等待结果或先停止当前任务"
                            return@direct
                        }
                        val map = seatMaps?.forRoom(config.room)
                        val mapError = map?.selectionError(config.seatNumbers)
                        val candidate = ImmediateBookingPlan.prepare(
                            if (map != null && mapError == null) map.bind(config) else config)
                        val error = if (map == null) "此阅览室尚无已核对的座位图，请重新选择房间"
                            else mapError ?: candidate.validationError()
                        if (error != null) {
                            status = error
                            return@direct
                        }
                        runCatching {
                            val request = ImmediateBookingPlan.intent(this@MainActivity, candidate)
                            ContextCompat.startForegroundService(this@MainActivity, request)
                        }.onSuccess {
                            status = "已启动直接预约，请在记录页查看学校返回结果；不代表预约成功"
                        }.onFailure {
                            status = "无法启动直接预约：${it.javaClass.simpleName}"
                            runtime.record("FAILED", status, "manual-failed:${java.util.UUID.randomUUID()}", "直接预约")
                        }
                    })
                    FixedBookingInfo()
                    FixedBookingButton(onClick = book@{
                            val map = seatMaps?.forRoom(config.room)
                            val mapError = map?.selectionError(config.seatNumbers)
                            val candidate = FixedBookingPlan.prepare(if (map != null && mapError == null) map.bind(config) else config)
                            val error = if (map == null) "此阅览室尚无已核对的座位图，请重新选择房间"
                                else mapError ?: candidate.validationError()
                            if (error != null) {
                                status = error
                                return@book
                            }
                            config = candidate
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            if (!scheduler.canScheduleExact()) {
                                pendingSchedule = config
                                exactPermission.launch(scheduler.exactAlarmSettingsIntent())
                                status = "请允许“闹钟和提醒”；返回应用后会继续安排下次 00:58 的预约"
                            } else arrange(config)
                    })
                    if (liveConfig.enabled) OutlinedButton(onClick = {
                        val cancelled = store.load().pendingTimes()
                        scheduler.cancel()
                        pendingSchedule = null
                        config = config.withPending(emptyList())
                        store.save(config)
                        cancelled.forEach { at -> runtime.record("CANCELLED", "用户取消了等待中的预约计划",
                            RuntimeStore.scheduledAction(at)) }
                        status = "已取消等待中的预约；不会取消学校已成功的预约"
                    }, modifier = Modifier.fillMaxWidth()) { Text("取消已安排预约") }
                    OutlinedButton(onClick = {
                        startService(Intent(this@MainActivity, BookingService::class.java).setAction(BookingService.ACTION_CANCEL))
                        status = "已请求停止当前任务；不取消已成功的学校预约，也不取消其他定时计划"
                    }, modifier = Modifier.fillMaxWidth()) { Text("停止当前任务") }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "“直接预约”现在尝试；“预约 · 下次 00:58 启动”只安排下一次一次性任务，已过则到次日。请核对两个入口各自的预计使用日期。需保持校园网可用；座位无法精确识别或提交结果不明时停止，不盲目重发。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(20.dp))
                  }
                }, permissions = {
                    PermissionsPage(
                        exact = exactAllowed,
                        notifications = notificationsAllowed,
                        battery = batteryAllowed,
                        testDetail = testDetail,
                        message = permissionStatus,
                        onExactAlarm = { exactPermission.launch(scheduler.exactAlarmSettingsIntent()) },
                        onNotifications = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                            }
                        },
                        onAppSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        },
                        onBatterySettings = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                        onPermissionGuide = { showPermissionSetup = true },
                        onWakeTest = {
                            if (!scheduler.canScheduleExact()) {
                                permissionStatus = "请允许精确闹钟，然后再次点击测试"
                                exactPermission.launch(scheduler.exactAlarmSettingsIntent())
                            } else {
                                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                runCatching { scheduler.scheduleWakeTest() }
                                    .onSuccess { permissionStatus = "60 秒后测试后台唤醒，请锁屏等待；不会预约，也不改变正式任务" }
                                    .onFailure { permissionStatus = it.message ?: "安排测试失败，请检查系统权限" }
                            }
                        }
                    )
                }, history = { RunHistoryPage(history) })
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
