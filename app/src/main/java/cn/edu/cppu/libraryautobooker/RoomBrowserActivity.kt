package cn.edu.cppu.libraryautobooker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.edu.cppu.libraryautobooker.data.RoomCatalogStore
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import java.time.LocalDate
import java.util.UUID

/** Reads only public room identities/time rules through the school's existing session. */
class RoomBrowserActivity : Activity() {
    internal data class RefreshRequest(val room: RoomProfile, val useDay: Int, val expectedDate: String, val requestId: String) {
        fun current(today: LocalDate = LocalDate.now()) = expectedDate == today.plusDays(useDay.toLong()).toString()
        fun matches(profile: RoomProfile, today: LocalDate = LocalDate.now()) = current(today) &&
            profile.id == room.id && profile.name == room.name && profile.groupId == room.groupId &&
            profile.groupName == room.groupName && profile.timeMode == room.timeMode &&
            profile.slotsDate == expectedDate && profile.validationError() == null
    }

    companion object {
        const val EXTRA_ROOM_PROFILE = "room_profile"
        const val EXTRA_USE_DAY = "use_day"
        const val EXTRA_EXPECTED_DATE = "expected_date"
        const val EXTRA_REFRESH_ERROR = "refresh_error"
        const val EXTRA_REQUEST_ID = "refresh_request_id"

        fun refreshIntent(context: Context, room: RoomProfile, reserveTomorrow: Boolean): Intent {
            require(room.validationError() == null)
            val day = if (reserveTomorrow) 1 else 0
            return Intent(context, RoomBrowserActivity::class.java)
                .putExtra(EXTRA_ROOM_PROFILE, room.toJson().toString())
                .putExtra(EXTRA_USE_DAY, day)
                .putExtra(EXTRA_EXPECTED_DATE, LocalDate.now().plusDays(day.toLong()).toString())
                .putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
        }

        internal fun refreshRequested(intent: Intent) = intent.hasExtra(EXTRA_ROOM_PROFILE) ||
            intent.hasExtra(EXTRA_USE_DAY) || intent.hasExtra(EXTRA_EXPECTED_DATE) || intent.hasExtra(EXTRA_REQUEST_ID)

        internal fun requestId(intent: Intent): String? = runCatching {
            intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf {
                it.length <= 80 && Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(it)
            }
        }.getOrNull()

        internal fun readRefreshRequest(intent: Intent): RefreshRequest? = runCatching {
            require(intent.hasExtra(EXTRA_ROOM_PROFILE) && intent.hasExtra(EXTRA_USE_DAY) && intent.hasExtra(EXTRA_EXPECTED_DATE))
            val text = intent.getStringExtra(EXTRA_ROOM_PROFILE) ?: error("missing room")
            require(text.length <= 32_000)
            val room = RoomProfile.fromJson(JSONObject(text)) ?: error("invalid room")
            val day = intent.getIntExtra(EXTRA_USE_DAY, -1)
            require(day in 0..1)
            val date = intent.getStringExtra(EXTRA_EXPECTED_DATE) ?: error("missing date")
            require(RoomProfile.validSlotsDate(date))
            val ticket = requestId(intent) ?: error("missing request ticket")
            RefreshRequest(room, day, date, ticket).also { require(it.current()) }
        }.getOrNull()
    }

    private lateinit var webView: WebView
    private lateinit var message: TextView
    private lateinit var roomList: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    // Separate from callback-generation cancellation so initial page loading is bounded too.
    private val targetDeadlineHandler = Handler(Looper.getMainLooper())
    private val targetTimeout = Runnable {
        if (targeted && !targetComplete) failTarget("刷新超过 45 秒，学校页面未能及时返回；请检查校园网后重新选择。")
    }
    private val catalog by lazy { RoomCatalogStore(this) }
    private val script by lazy { assets.open("room-discovery.js").bufferedReader().use { it.readText() } }
    private val route = LibraryRoute.DIRECT
    private val groups = linkedMapOf<String, String>()
    private val pendingGroups = ArrayDeque<String>()
    private val collected = linkedMapOf<String, RoomProfile>()
    private val unavailable = linkedMapOf<String, Pair<String, String>>()
    private var currentGroupId = "0"
    private var expectedGroup: String? = null
    private var syncing = false
    private var active = false
    private var closed = false
    private var generation = 0
    private var deadline = 0L
    private var reloadAfterLogin = false
    private var pageLoaded = false
    private var targetRefresh: RefreshRequest? = null
    private var targeted = false
    private var targetAutoStarted = false
    private var targetStopped = false
    private var targetComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targeted = refreshRequested(intent)
        targetRefresh = if (targeted) readRefreshRequest(intent) else null
        CookieManager.getInstance().setAcceptCookie(true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 0) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + 12, bars.top + 12, bars.right + 12, bars.bottom)
            insets
        }
        message = TextView(this).apply {
            text = "正在读取学校阅览室列表。同步只读取房间和时间规则，不会预约座位。"
            setPadding(8, 8, 8, 12)
        }
        root.addView(message)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "同步全部楼层"
            isEnabled = !targeted
            setOnClickListener {
                val ids = listOf("0") + groups.keys.toList()
                startSync(ids)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "选择楼层同步"; isEnabled = !targeted; setOnClickListener { chooseGroup() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "登录 / 重新读取"
            setOnClickListener {
                cancelSync()
                if (targeted) {
                    failTarget("请先返回应用完成登录，再重新选择房间或使用日刷新时段。")
                    return@setOnClickListener
                }
                reloadAfterLogin = true
                startActivity(Intent(this@RoomBrowserActivity, LoginActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        root.addView(Button(this).apply {
            text = "停止同步"
            setOnClickListener {
                cancelSync()
                if (targeted) failTarget("已停止刷新；未发出预约请求。")
                else { message.text = "已停止同步；未发出预约请求。"; renderRooms() }
            }
        })
        roomList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(roomList) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        webView = WebView(this).apply {
            // This is a read-only data loader, never another interactive booking UI.
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
            isLongClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnTouchListener { _, _ -> true }
            setOnKeyListener { _, _, _ -> true }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (allowedRequest(request.method, request.url.toString())) null else blockedResponse()
                @Suppress("DEPRECATION")
                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                    if (allowedRequest("GET", url)) null else blockedResponse()
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return rejectNavigation(request.url.toString())
                }
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = rejectNavigation(url)
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageLoaded = false
                    if (!allowedPage(url)) {
                        view.stopLoading()
                        stopWithMessage("学校登录页或其他页面不能用于刷新时段，请先返回应用完成登录。")
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!targetComplete && view.url == url && allowedPage(url)) {
                        pageLoaded = true
                        if (active && !closed) inspectPage(url)
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) stopWithMessage("学校页面未能加载，请检查校园网后重新读取。")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400) {
                        stopWithMessage(if (response.statusCode == 429) "学校要求降低访问频率，已停止同步，请稍后再试。" else "学校页面暂时不可用，已停止同步。")
                    }
                }
                override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: android.net.http.SslError) {
                    sslHandler.cancel()
                    stopWithMessage("学校连接的证书验证未通过，已停止同步；不会忽略证书错误。")
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(0, 0))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        renderRooms()
        if (targeted && targetRefresh == null) failTarget("刷新目标或使用日已失效，请返回应用重新选择；未采用其他房间的规则。")
        else {
            if (targeted) targetDeadlineHandler.postDelayed(targetTimeout, 45_000L)
            webView.loadUrl(route.url("/selectreadingroom") +
                (targetRefresh?.let { "?groupid=${it.room.groupId}" } ?: ""))
        }
    }

    private fun allowedPage(url: String) = route.pathOf(url) == "/selectreadingroom"

    private fun allowedRequest(method: String, url: String): Boolean {
        val requestRoute = route
        val path = requestRoute.pathOf(url)
        if (method == "POST") return path in setOf("/readingroom/checktimecanbesk", "/readingroom/getbesktimelist")
        if (method != "GET") return false
        if (path == "/selectreadingroom") return true
        // Static files are necessary for the school's own jQuery processing.
        // Other GET endpoints (including seat maps and state-changing URLs) stay blocked.
        val staticFile = Regex("(?i)^/(?:js|assets|css|images|fonts|plugins|scripts)/[a-z0-9_./@-]+\\.(?:js|css|png|jpe?g|gif|svg|ico|woff2?|ttf|eot)$")
        if (path != null && staticFile.matches(path) && path.split('/').none { it == ".." }) return true
        return false
    }

    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Read-only room discovery",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun rejectNavigation(url: String): Boolean {
        if (allowedPage(url)) {
            // A user-selected group cancels the automatic sequence before the next read.
            if (syncing && runCatching { URI(url).rawQuery }.getOrNull() != "groupid=$expectedGroup")
                stopWithMessage("学校楼层页面已改变，已停止本次同步。")
            return false
        }
        stopWithMessage("此窗口只读取阅览室列表；如登录过期，请返回应用先完成登录。")
        return true
    }

    private fun evaluate(expression: String, token: Int, pageUrl: String, callback: (JSONObject) -> Unit) {
        if (closed || targetComplete || !active || token != generation || webView.url != pageUrl || !allowedPage(pageUrl)) return
        webView.evaluateJavascript(expression) { encoded ->
            if (closed || targetComplete || !active || token != generation || webView.url != pageUrl) return@evaluateJavascript
            val result = runCatching {
                require(encoded.length <= 200_000)
                JSONObject(JSONArray("[$encoded]").getString(0))
            }.getOrNull()
            if (result == null) {
                stopWithMessage("未能读取学校房间数据，未同步未确认的规则。")
            } else callback(result)
        }
    }

    private fun inspectPage(url: String) {
        val token = generation
        evaluate(script + "\nJSON.stringify(window.LibraryRoomDiscovery?.snapshot() || {kind:'unsupported'})", token, url) { data ->
            if (data.optString("kind") != "ready") {
                stopWithMessage(if (data.optString("kind") == "login-required") "学校登录已过期，请先登录再同步阅览室。" else "学校没有提供可识别的阅览室列表，请登录后重新读取。")
                return@evaluate
            }
            val rows = data.optJSONArray("groups") ?: JSONArray()
            if (rows.length() > 40) { stopWithMessage("学校楼层列表异常，已停止读取。"); return@evaluate }
            val found = linkedMapOf<String, String>()
            for (index in 0 until rows.length()) {
                val group = rows.optJSONObject(index) ?: continue
                val id = group.optString("id")
                val name = group.optString("name")
                if (Regex("^[1-9]\\d{0,8}$").matches(id) && name.isNotBlank() && name.length <= 80) found[id] = name
            }
            groups.clear(); groups.putAll(found)
            currentGroupId = data.optString("groupId", "0")
            val target = targetRefresh
            if (targeted) {
                if (targetStopped) return@evaluate
                if (target == null || !target.current()) {
                    failTarget("使用日已跨午夜变化，请返回应用重新选择；未保存本次时段。")
                    return@evaluate
                }
                val actualRooms = data.optJSONArray("rooms") ?: JSONArray()
                val matches = (0 until actualRooms.length()).mapNotNull { actualRooms.optJSONObject(it) }
                    .filter { it.optString("id") == target.room.id }
                val actual = matches.singleOrNull()
                if (currentGroupId != target.room.groupId || actual == null ||
                    actual.optString("name") != target.room.name || actual.optString("groupId") != target.room.groupId ||
                    actual.optString("groupName") != target.room.groupName || actual.optString("timeMode") != target.room.timeMode) {
                    failTarget("学校当前房间、楼层或时间模式与选择不一致，已停止刷新。")
                    return@evaluate
                }
                if (!targetAutoStarted) {
                    targetAutoStarted = true
                    startSync(listOf(target.room.groupId))
                    return@evaluate
                }
                if (!syncing) return@evaluate
            }
            if (syncing) {
                if (currentGroupId != expectedGroup) { stopWithMessage("学校返回的楼层与目标不一致，已停止同步。"); return@evaluate }
                val options = target?.let { JSONObject().put("roomId", it.room.id).put("useDay", it.useDay).toString() }.orEmpty()
                evaluate("JSON.stringify(window.LibraryRoomDiscovery.start($options))", token, url) { poll(token, url, it) }
            } else {
                message.text = "已读取 ${groups.size} 个楼层。点“同步全部楼层”读取各阅览室；下方已有同步记录可直接选择。"
                renderRooms()
            }
        }
    }

    private fun chooseGroup() {
        if (groups.isEmpty()) { message.text = "尚未读取学校楼层列表，请先登录后重新读取。"; return }
        val entries = groups.entries.toList()
        AlertDialog.Builder(this).setTitle("选择学校实际提供的楼层")
            .setItems(entries.map { it.value }.toTypedArray()) { _, index -> startSync(listOf(entries[index].key)) }
            .setNegativeButton("取消", null).show()
    }

    private fun startSync(ids: List<String>) {
        if (!active || closed) return
        if (targeted && (targetStopped || targetRefresh?.current() != true || ids != listOf(targetRefresh!!.room.groupId))) {
            failTarget("刷新目标或使用日已改变，请返回应用重新选择。")
            return
        }
        if (syncing) { message.text = "正在顺序同步，请等待完成或先停止。"; return }
        if (ids.isEmpty() || ids.size > 40 || ids.any { !Regex("^(?:0|[1-9]\\d{0,8})$").matches(it) || it != "0" && it !in groups }) {
            if (targeted) failTarget("学校尚未确认所选楼层，未读取或采用其他楼层的时段。")
            else message.text = "楼层尚未从学校确认，请先重新读取。"
            return
        }
        cancelSync()
        syncing = true
        deadline = SystemClock.elapsedRealtime() + 300_000L
        collected.clear(); unavailable.clear()
        pendingGroups.addAll(ids.distinct())
        renderRooms()
        nextGroup()
    }

    private fun nextGroup() {
        if (!syncing || !active || closed) return
        if (SystemClock.elapsedRealtime() >= deadline) { stopWithMessage("同步超过五分钟，已停止；请按楼层分次同步。"); return }
        if (pendingGroups.isEmpty()) {
            val count = collected.size
            val target = targetRefresh
            if (targeted && (target == null || collected.size != 1 || !target.matches(collected.values.single()))) {
                failTarget("未取得所选房间和使用日的完整时段，请返回应用重新选择；未采用其他日期。")
                return
            }
            runCatching { catalog.merge(collected.values.toList()) }.onFailure {
                stopWithMessage("房间规则校验或保存失败，未使用未确认的数据。"); return
            }
            syncing = false; expectedGroup = null
            if (target != null) {
                val room = collected.values.single()
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ROOM_PROFILE, room.toJson().toString())
                    .putExtra(EXTRA_USE_DAY, target.useDay).putExtra(EXTRA_EXPECTED_DATE, target.expectedDate)
                    .putExtra(EXTRA_REQUEST_ID, target.requestId))
                targetComplete = true
                targetDeadlineHandler.removeCallbacks(targetTimeout)
                finish()
                return
            }
            message.text = "同步完成：$count 个阅览室可选，${unavailable.size} 个暂未取得完整时间规则。同步不代表学校允许立即预约。"
            renderRooms(); return
        }
        expectedGroup = pendingGroups.removeFirst()
        generation++
        message.text = "正在同步 ${groups[expectedGroup] ?: "所有分组"}，只读取房间和时段，不会预约。"
        webView.loadUrl(route.url("/selectreadingroom") + "?groupid=$expectedGroup")
    }

    private fun poll(token: Int, pageUrl: String, data: JSONObject) {
        if (!syncing || token != generation || closed || !active) return
        if (targeted && targetRefresh?.current() != true) { failTarget("使用日已跨午夜变化，已停止刷新；请返回应用重新选择。"); return }
        if (SystemClock.elapsedRealtime() >= deadline) { stopWithMessage("同步超过五分钟，已停止；请按楼层分次同步。"); return }
        when (data.optString("kind")) {
            "running" -> {
                message.text = "正在同步 ${groups[expectedGroup] ?: "所有分组"}：${data.optInt("completed")} / ${data.optInt("total")} 个阅览室。"
                handler.postDelayed({ evaluate("JSON.stringify(window.LibraryRoomDiscovery.status())", token, pageUrl) { poll(token, pageUrl, it) } }, 450L)
            }
            "done" -> {
                val profiles = data.optJSONArray("profiles") ?: JSONArray()
                val rejected = data.optJSONArray("unavailable") ?: JSONArray()
                if (profiles.length() > 200 || rejected.length() > 200) { stopWithMessage("学校房间数据超过安全上限，已停止同步。"); return }
                if (targeted && (profiles.length() != 1 || rejected.length() != 0)) {
                    val detail = rejected.optJSONObject(0)?.let { reason(it.optString("reason")) }
                        ?: "学校未返回所选使用日的完整时段"
                    failTarget("刷新失败：$detail；未采用旧规则或其他使用日。")
                    return
                }
                for (index in 0 until profiles.length()) {
                    val room = profiles.optJSONObject(index)?.let(RoomProfile::fromJson)
                    val previous = room?.let { collected[it.id] }
                    val promoted = previous != null && room != null && previous.groupId == "0" &&
                        previous.copy(groupId = room.groupId, groupName = room.groupName) == room
                    if (room == null || room.groupId != expectedGroup || previous != null && previous != room && !promoted ||
                        targeted && targetRefresh?.matches(room) != true) {
                        stopWithMessage("房间、楼层或使用日规则不一致，已停止同步，未保存本轮数据。"); return
                    }
                    collected[room.id] = room
                    unavailable.remove(room.id)
                }
                for (index in 0 until rejected.length()) {
                    val room = rejected.optJSONObject(index) ?: continue
                    val id = room.optString("id")
                    val name = room.optString("name")
                    if (id !in collected && Regex("^[1-9]\\d{0,8}$").matches(id) && name.isNotBlank() && name.length <= 80) unavailable[id] = name to reason(room.optString("reason"))
                }
                if (collected.size + unavailable.size > 200) { stopWithMessage("房间数量超过安全上限，已停止同步。"); return }
                handler.postDelayed({ if (token == generation && active) nextGroup() }, 450L)
            }
            else -> {
                val detail = reason(data.optString("reason", data.optString("kind")))
                stopWithMessage("同步已停止：$detail。本轮未保存未完成的数据。"); renderRooms()
            }
        }
    }

    private fun reason(value: String) = when (value) {
        "login-required" -> "学校登录过期，请先登录"
        "rate-limited" -> "学校要求降低访问频率，请稍后再试"
        "network-error" -> "访问学校网络失败，请检查当前线路"
        "school-not-available" -> "学校暂未允许读取时段（可能尚未开放或权限不足）"
        "time-mode-unknown" -> "学校时间选择方式尚未识别"
        "time-rules-unavailable" -> "未取得完整、匹配此房间的固定时段"
        "page-changed" -> "学校页面已变化，请重新读取"
        "school-ajax-unavailable" -> "学校页面脚本尚未准备好"
        else -> "学校返回数据无法确认，未采用未验证的规则"
    }

    private fun renderRooms() {
        if (!::roomList.isInitialized) return
        roomList.removeAllViews()
        if (targeted) {
            targetRefresh?.let { target ->
                roomList.addView(TextView(this).apply {
                    text = "正在刷新：${target.room.groupName} · ${target.room.name}\n使用日：${target.expectedDate}\n只读取该房间当天时段，不会提交预约。"
                    setPadding(12, 12, 12, 12)
                })
            }
            return
        }
        for (room in catalog.load()) {
            if (room.id in unavailable) continue
            roomList.addView(Button(this).apply {
                text = "${room.groupName} · ${room.name}\n${if (room.timeMode == "free") "自选起止时间" else "${room.slots.size} 个固定时段"}"
                isEnabled = !syncing
                setOnClickListener {
                    val verified = catalog.find(room.id)
                    if (verified != room || room.validationError() != null) { message.text = "房间规则已变化，请重新同步后选择。"; return@setOnClickListener }
                    setResult(RESULT_OK, Intent().putExtra("room_id", room.id))
                    finish()
                }
            })
        }
        for ((_, item) in unavailable) roomList.addView(TextView(this).apply { text = "${item.first}：${item.second}"; setPadding(12, 12, 12, 12) })
    }

    private fun cancelSync() {
        generation++
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized && !closed && allowedPage(webView.url.orEmpty())) {
            webView.evaluateJavascript("window.LibraryRoomDiscovery?.cancel()", null)
        }
        syncing = false
        expectedGroup = null
        pendingGroups.clear()
    }

    private fun failTarget(detail: String) {
        if (!targeted || targetComplete) return
        targetStopped = true
        cancelSync()
        val result = Intent().putExtra(EXTRA_REFRESH_ERROR, detail)
            .putExtra(EXTRA_REQUEST_ID, targetRefresh?.requestId ?: requestId(intent))
        targetRefresh?.let { result.putExtra(EXTRA_USE_DAY, it.useDay).putExtra(EXTRA_EXPECTED_DATE, it.expectedDate) }
        setResult(RESULT_CANCELED, result)
        if (::message.isInitialized) message.text = "$detail\n本次刷新不会自动重试，也未提交预约。"
        targetComplete = true
        targetDeadlineHandler.removeCallbacks(targetTimeout)
        finish()
    }

    private fun stopWithMessage(detail: String) {
        if (targeted) failTarget(detail)
        else { cancelSync(); message.text = detail }
    }

    override fun onResume() {
        super.onResume(); active = true
        if (targetComplete) return
        if (::webView.isInitialized) {
            webView.onResume()
            if (reloadAfterLogin) { reloadAfterLogin = false; webView.loadUrl(route.url("/selectreadingroom")) }
            else if (pageLoaded && allowedPage(webView.url.orEmpty()) && !syncing) inspectPage(webView.url!!)
        }
    }

    override fun onPause() {
        active = false
        if (targeted && !targetComplete) failTarget("已离开时段刷新页面，后台读取已停止，请重新选择房间或使用日。")
        if (syncing) message.text = "已离开同步页面，后台读取已停止；返回后请重新同步。"
        cancelSync()
        reloadAfterLogin = true
        if (::webView.isInitialized) { webView.stopLoading(); webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        targetDeadlineHandler.removeCallbacks(targetTimeout)
        cancelSync(); closed = true
        CookieManager.getInstance().flush()
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
}
