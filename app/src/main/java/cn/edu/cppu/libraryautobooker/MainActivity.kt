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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import cn.edu.cppu.libraryautobooker.update.UpdateInstallLaunch
import cn.edu.cppu.libraryautobooker.update.UpdatePolicy
import cn.edu.cppu.libraryautobooker.update.UpdateState
import cn.edu.cppu.libraryautobooker.update.UpdateViewModel
import androidx.core.app.NotificationManagerCompat
import cn.edu.cppu.libraryautobooker.booking.FixedBookingPlan
import cn.edu.cppu.libraryautobooker.booking.ImmediateBookingPlan
import androidx.core.content.ContextCompat
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val updateViewModel: UpdateViewModel by viewModels()
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
        enableEdgeToEdge()
        val store = ConfigStore(this)
        val scheduler = BookingScheduler(this)
        val runtime = RuntimeStore(this)
        runtime.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val setupPrefs = getSharedPreferences("permission_setup", MODE_PRIVATE)
        getSharedPreferences("booking_config", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)

        setContent {
            LibraryAutoBookerTheme {
                LaunchedEffect(loginEntryVisible) {
                    uiHandler.removeCallbacks(sessionProbe)
                    if (loginEntryVisible) cancelSessionChecks()
                    else if (foreground) uiHandler.post(sessionProbe)
                }
                val oemProfile = remember { OemProfiles.current() }
                var showPermissionSetup by rememberSaveable { mutableStateOf(!setupPrefs.getBoolean("shown", false)) }
                if (showPermissionSetup && !loginEntryVisible) PermissionSetup(profile = oemProfile) {
                    setupPrefs.edit().putBoolean("shown", true).apply()
                    showPermissionSetup = false
                    refreshVersion++
                }
                var config by remember { mutableStateOf(store.load()) }
                var rooms by remember { mutableStateOf(RoomCatalogStore(this@MainActivity).load()) }
                val seatMaps = remember { runCatching { SeatMapCatalog.load(this@MainActivity) }.getOrNull() }
                var status by remember { mutableStateOf("") }
                var permissionStatus by remember { mutableStateOf("") }
                var pendingSchedule by remember { mutableStateOf<BookingConfig?>(null) }
                var savedEnabled by remember { mutableStateOf(config.enabled) }
                var rulesLoading by remember { mutableStateOf(false) }
                var rulesStatus by remember { mutableStateOf("使用各房间本地保存的时间表；切换房间、日期不会在线同步。") }
                var rulesTicket by remember { mutableStateOf<RoomRulesTicket?>(null) }
                var rulesRequestId by remember { mutableStateOf<String?>(null) }
                val liveConfig = remember(refreshVersion) { store.load() }
                LaunchedEffect(refreshVersion) {
                    if (liveConfig.enabled != savedEnabled || liveConfig.scheduledAtMillis != config.scheduledAtMillis) {
                        config = config.copy(enabled = liveConfig.enabled, scheduledAtMillis = liveConfig.scheduledAtMillis,
                            scheduledTimes = liveConfig.scheduledTimes)
                        savedEnabled = liveConfig.enabled
                    }
                }
                val taskDetail = remember(refreshVersion) { runtime.prefs.getString("task_detail", "尚未安排任务").orEmpty() }
                val taskState = remember(refreshVersion) { runtime.prefs.getString("task_state", "").orEmpty() }
                val running = BookingService.running || taskState in setOf("RUNNING", "TRIGGERED")
                val updateState by updateViewModel.state.collectAsState()
                val updatePendingTimes = remember(refreshVersion) { store.load().pendingTimes() }
                val updateSafe = UpdatePolicy.isSafe(
                    serviceRunning = BookingService.running,
                    taskState = taskState,
                    pendingTimes = updatePendingTimes,
                    now = System.currentTimeMillis(),
                    loginVisible = loginEntryVisible,
                    permissionSetupVisible = showPermissionSetup
                )
                var updatePromptVisible by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(refreshVersion, updateSafe) {
                    updateViewModel.onResume()
                    if (updateSafe) updateViewModel.maybeAutoCheck(true)
                }
                LaunchedEffect(updateSafe) {
                    if (!updateSafe) {
                        if (updateState is UpdateState.Checking) updateViewModel.cancelCheck()
                        if (updateState is UpdateState.Downloading) updateViewModel.cancelDownload()
                        updatePromptVisible = false
                    }
                }
                LaunchedEffect(updateState, updateSafe) {
                    val current = updateState
                    if (updateSafe && current is UpdateState.Available && !current.deferred) {
                        updatePromptVisible = true
                    } else if (current is UpdateState.Idle || current is UpdateState.Checking ||
                        current is UpdateState.UpToDate ||
                        (current is UpdateState.Available && current.deferred)) {
                        updatePromptVisible = false
                    }
                }
                val history = remember(refreshVersion) { runtime.history() }
                var watchedManualId by rememberSaveable { mutableStateOf<String?>(null) }
                var feedbackTitle by rememberSaveable { mutableStateOf<String?>(null) }
                var feedbackDetail by rememberSaveable { mutableStateOf("") }
                LaunchedEffect(history, watchedManualId) {
                    val id = watchedManualId
                    val record = history.firstOrNull { it.id == id }
                    val result = record?.let(DirectBookingFeedback::from)
                    if (result != null) {
                        watchedManualId = null
                        feedbackTitle = result.title
                        feedbackDetail = result.detail
                    }
                }
                if (foreground && feedbackTitle != null) DirectBookingFeedbackDialog(feedbackTitle!!, feedbackDetail) {
                    feedbackTitle = null; feedbackDetail = ""
                }
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
                var pendingInstallAfterPermission by remember { mutableStateOf(false) }
                val installActionRef = remember { InstallActionRef() }
                val installPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    updateViewModel.onResume()
                    if (pendingInstallAfterPermission) {
                        pendingInstallAfterPermission = false
                        if (updateViewModel.state.value is UpdateState.Ready) {
                            installActionRef.action?.invoke()
                        } else {
                            permissionStatus = "如果已完成安装，请重新打开应用使用新版本"
                        }
                    }
                }
                val attemptInstall: () -> Unit = {
                    if (updateSafe) {
                        when (val result = updateViewModel.installNow()) {
                            UpdateInstallLaunch.Started ->
                                permissionStatus = "已打开系统安装器，请确认覆盖安装"
                            UpdateInstallLaunch.PermissionRequired -> {
                                pendingInstallAfterPermission = true
                                runCatching { installPermission.launch(updateViewModel.installSettingsIntent()) }
                                    .onFailure {
                                        pendingInstallAfterPermission = false
                                        runCatching { startActivity(updateViewModel.appDetailsIntent()) }
                                        permissionStatus = "请在本应用的“安装未知应用”权限中允许后重试"
                                    }
                            }
                            is UpdateInstallLaunch.Failed -> permissionStatus = result.message
                        }
                    }
                }
                installActionRef.action = attemptInstall
                val openUpdatePage: () -> Unit = {
                    if (!updateViewModel.openPage()) {
                        permissionStatus = "未配置发布页，请稍后重试或联系维护者"
                    }
                }
                val selectRoom: (RoomProfile) -> Unit = { room ->
                    val currentRoom = config.room
                    val sameIdentity = room.id == currentRoom.id && room.name == currentRoom.name &&
                        room.groupId == currentRoom.groupId && room.groupName == currentRoom.groupName &&
                        room.timeMode == currentRoom.timeMode
                    if (!sameIdentity) {
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
                    } else if (room != currentRoom) {
                        val oldPlan = store.load()
                        val affected = roomRulesAffectPlan(oldPlan, room)
                        val preserve = oldPlan.enabled && !affected
                        val adopted = applyRoomRules(config, room)
                        if (affected) {
                            scheduler.cancel(); pendingSchedule = null
                            oldPlan.pendingTimes().forEach { at -> runtime.record("CANCELLED",
                                "本地时段已更新，请核对后重新安排对应日期的计划", RuntimeStore.scheduledAction(at)) }
                        }
                        config = if (preserve) adopted.withPending(oldPlan.pendingTimes()) else adopted
                        if (!preserve) store.save(config.withPending(emptyList()))
                    }
                    rulesStatus = "已读取 ${room.name} 的本地时间表；未发起在线同步。"
                }
                val roomBrowser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    rooms = RoomCatalogStore(this@MainActivity).load()
                    val id = if (result.resultCode == RESULT_OK) result.data?.getStringExtra("room_id") ?: config.room.id
                        else config.room.id
                    if (result.resultCode == RESULT_OK) rooms.firstOrNull { it.id == id }?.let(selectRoom)
                }
                val roomRulesRefresh = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val ticket = rulesTicket
                    val requestId = rulesRequestId
                    rulesTicket = null
                    rulesRequestId = null
                    rulesLoading = false
                    val data = result.data
                    val responseId = data?.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID)
                    if (ticket == null || data == null || requestId == null || responseId != requestId) {
                        rulesStatus = "刷新已取消或返回结果已失效；仍保留原缓存，未改变预约目标。"
                    } else if (result.resultCode != RESULT_OK) {
                        val detail = data.getStringExtra(RoomBrowserActivity.EXTRA_REFRESH_ERROR).orEmpty().take(180)
                        rulesStatus = "未取得当前房间／使用日的最新时段：${detail.ifBlank { "本次读取未完成" }} 当前仅保留缓存；实际预约仍会精确核对。"
                    } else {
                        val updated = runCatching {
                            val text = data.getStringExtra(RoomBrowserActivity.EXTRA_ROOM_PROFILE) ?: error("missing rules")
                            require(text.length <= 32_000)
                            RoomProfile.fromJson(JSONObject(text)) ?: error("invalid rules")
                        }.getOrNull()
                        val expectedNow = LocalDate.now().plusDays(if (config.reserveTomorrow) 1L else 0L).toString()
                        val currentTicket = runCatching { RoomRulesTicket.create(config, LocalDate.parse(expectedNow)) }.getOrNull()
                        if (updated == null || currentTicket != ticket ||
                            !ticket.accepts(updated, data.getIntExtra(RoomBrowserActivity.EXTRA_USE_DAY, -1),
                                data.getStringExtra(RoomBrowserActivity.EXTRA_EXPECTED_DATE).orEmpty())) {
                            rulesStatus = "房间、使用日或日期已经改变，已忽略旧刷新结果；请重新刷新。"
                        } else if (BookingService.running) {
                            rulesStatus = "预约任务已开始，本次未改动运行目标；如需更新，请结束后手动操作。"
                        } else runCatching {
                            val previous = config
                            val changed = roomRulesChanged(previous.room, updated)
                            val oldPlan = store.load()
                            val affectedPlan = roomRulesAffectPlan(oldPlan, updated)
                            val preservePlan = oldPlan.enabled && !affectedPlan
                            val adopted = applyRoomRules(previous, updated).let {
                                if (preservePlan) it.withPending(oldPlan.pendingTimes()) else it
                            }
                            if (affectedPlan) {
                                scheduler.cancel()
                                pendingSchedule = null
                                oldPlan.pendingTimes().forEach { at -> runtime.record("CANCELLED",
                                    "阅览室时间规则已更新，旧定时计划已取消，请核对后重新安排", RuntimeStore.scheduledAction(at)) }
                            }
                            config = adopted
                            // A metadata-only refresh must not overwrite an independently
                            // queued plan with the user's unsaved foreground draft.
                            if (!preservePlan) store.save(adopted.withPending(emptyList()))
                            rooms = RoomCatalogStore(this@MainActivity).load()
                            val selectionCleared = previous.selectedSlots.isNotEmpty() && adopted.selectedSlots.isEmpty()
                            rulesStatus = "已更新 ${updated.name} · ${updated.slotsDate} 的真实时段。" + (
                                if (selectionCleared) "原选时间已变化，请重新选择使用时段；座位已保留。"
                                else if (changed) "已按精确时间保留有效选择。"
                                else "时间未变化，原选择已保留。") +
                                if (affectedPlan) "对应使用日的旧定时计划已取消，请核对后重新安排。"
                                else if (preservePlan) "独立的已安排计划保持不变。" else ""
                        }.onFailure { rulesStatus = "新时段未能安全应用，未采用不确定的规则；请重新刷新。" }
                    }
                }

                // Online rules loading is exclusively a user-invoked action.
                val refreshRules: () -> Unit = {
                    if (!rulesLoading && !BookingService.running && config.room.validationError() == null) {
                        runCatching {
                            val request = RoomBrowserActivity.refreshIntent(this@MainActivity, config.room, config.reserveTomorrow)
                            val expected = LocalDate.parse(request.getStringExtra(RoomBrowserActivity.EXTRA_EXPECTED_DATE))
                            rulesTicket = RoomRulesTicket.create(config, expected)
                            rulesRequestId = request.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID)
                            rulesLoading = true
                            rulesStatus = "正在读取 ${config.room.name} · $expected 的时段；不会提交预约。"
                            roomRulesRefresh.launch(request)
                        }.onFailure {
                            rulesLoading = false; rulesTicket = null; rulesRequestId = null
                            rulesStatus = "无法启动时段刷新，当前仅为缓存；请稍后重新刷新。"
                        }
                    } else if (BookingService.running) rulesStatus = "预约任务运行中，暂不刷新；任务结束后可手动更新。"
                }

                // Keep draft reservation settings alive while an expired session is restored.
                if (loginEntryVisible) {
                    EntryLoginScreen(autoEnterValid = !managingLogin) {
                        loginEntryVisible = false; managingLogin = false; refreshVersion++
                    }
                    return@LibraryAutoBookerTheme
                }

                val loginStatuses = rememberLoginStatuses()
                LibraryAppPages(booking = {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TaskSummaryCard(liveConfig, taskState, taskDetail, running)
                        LoginStatusCard(loginStatuses, onCheck = {
                            SessionChecker.probe(this@MainActivity, LibraryRoute.DIRECT, updateSession = false) {}
                        }, hasActions = true) {
                            val credentials = remember(refreshVersion) { CredentialStore(this@MainActivity) }
                            Text(
                                if (!credentials.configured) "自动重新登录：未设置账号密码"
                                else if (credentials.paused) "自动重新登录：已暂停，请核对账号密码后重新登录"
                                else if (credentials.enabled) "自动重新登录：已开启" else "自动重新登录：已关闭"
                            )
                            OutlinedButton(onClick = { managingLogin = true; loginEntryVisible = true },
                                modifier = Modifier.fillMaxWidth()) { Text("登录 / 管理账号") }
                            OutlinedButton(onClick = {
                                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                            }, modifier = Modifier.fillMaxWidth()) { Text("直连 · 图书馆网页登录") }
                        }
                        PreparationCard(loginStatuses.direct.state, config,
                            seatMaps?.forRoom(config.room) != null, liveConfig.enabled)
                        RoomSettingsCard(config.room, rooms, onSelect = selectRoom, onSync = {
                            roomBrowser.launch(Intent(this@MainActivity, RoomBrowserActivity::class.java))
                        })
                        SeatMapCard(seatMaps?.forRoom(config.room), config.seatNumbers) { selected ->
                            val draft = config.copy(seatNumbers = selected, seatChoices = emptyList(), mapSeats = emptyList())
                            val map = seatMaps?.forRoom(draft.room)
                            config = if (map != null && map.selectionError(selected) == null) map.bind(draft) else draft
                        }
                        BookingDateTimeCard(
                            config = config,
                            rulesLoading = rulesLoading,
                            rulesStatus = rulesStatus,
                            onRefreshRules = refreshRules,
                            onConfigChange = { config = it },
                            onMessage = { status = it }
                        )
                        BookingActionsCard(
                            scheduled = liveConfig.enabled,
                            running = running,
                            directEnabled = !rulesLoading && watchedManualId == null && !running,
                            scheduledEnabled = !rulesLoading && !running,
                            directPending = watchedManualId != null,
                            status = status,
                            onDirect = direct@{
                                if (BookingService.running) {
                                    status = "已有预约任务正在运行，请等待结果或先停止当前任务"
                                    feedbackTitle = "直接预约未启动"; feedbackDetail = status
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
                                    feedbackTitle = "请检查预约设置"; feedbackDetail = error
                                    return@direct
                                }
                                runCatching {
                                    val request = ImmediateBookingPlan.intent(this@MainActivity, candidate)
                                    watchedManualId = requireNotNull(ImmediateBookingPlan.actionId(request))
                                    ContextCompat.startForegroundService(this@MainActivity, request)
                                }.onSuccess {
                                    status = "已启动直接预约，结果将弹窗提示；不代表预约成功"
                                }.onFailure {
                                    status = "无法启动直接预约：${it.javaClass.simpleName}"
                                    val failedId = watchedManualId
                                    watchedManualId = null
                                    feedbackTitle = "直接预约未启动"; feedbackDetail = status
                                    runtime.record("FAILED", status, failedId ?: "manual-failed:${java.util.UUID.randomUUID()}", "直接预约")
                                }
                            },
                            onSchedule = book@{
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
                            },
                            onCancelSchedule = {
                                val cancelled = store.load().pendingTimes()
                                scheduler.cancel()
                                pendingSchedule = null
                                config = config.withPending(emptyList())
                                store.save(config)
                                cancelled.forEach { at -> runtime.record("CANCELLED", "用户取消了等待中的预约计划",
                                    RuntimeStore.scheduledAction(at)) }
                                status = "已取消等待中的预约；不会取消学校已成功的预约"
                            },
                            onStop = {
                                startService(Intent(this@MainActivity, BookingService::class.java).setAction(BookingService.ACTION_CANCEL))
                                status = "已请求停止当前任务；不取消已成功的学校预约，也不取消其他定时计划"
                            }
                        )
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
                        },
                        profile = oemProfile,
                        footer = {
                            if (updateViewModel.configured) {
                                UpdateCard(
                                    state = updateState,
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    safe = updateSafe,
                                    onCheck = { if (updateSafe) updateViewModel.checkNow() },
                                    onDownload = { if (updateSafe) updateViewModel.download() },
                                    onCancelDownload = { updateViewModel.cancelDownload() },
                                    onInstall = attemptInstall,
                                    onOpenPage = openUpdatePage
                                )
                            }
                        }
                    )
                }, history = { RunHistoryPage(history) })
                if (updatePromptVisible) {
                    UpdateDialog(
                        state = updateState,
                        currentVersion = BuildConfig.VERSION_NAME,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        safe = updateSafe,
                        onDismiss = {
                            if (updateState is UpdateState.Available) updateViewModel.defer()
                            updatePromptVisible = false
                        },
                        onDownload = { if (updateSafe) updateViewModel.download() },
                        onCancelDownload = { updateViewModel.cancelDownload() },
                        onInstall = attemptInstall,
                        onRetry = { updateViewModel.retry() },
                        onOpenPage = openUpdatePage
                    )
                }
            }
        }
    }
}

private class InstallActionRef {
    var action: (() -> Unit)? = null
}
