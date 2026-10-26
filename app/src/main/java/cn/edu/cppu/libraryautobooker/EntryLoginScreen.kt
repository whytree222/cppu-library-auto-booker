package cn.edu.cppu.libraryautobooker

import android.app.Activity
import android.content.Intent
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** The entry page contains only login controls, not the reservation dashboard. */
@Composable
fun EntryLoginScreen(
    autoEnterValid: Boolean = true,
    sessionCheck: ((Context, (String) -> Unit) -> Unit)? = null,
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember { CredentialStore(app) }
    var username by remember { mutableStateOf(store.savedUsername()) }
    // Deliberately NOT rememberSaveable: passwords must not enter a Bundle or saved state.
    var password by remember { mutableStateOf("") }
    var autoRelogin by remember { mutableStateOf(store.preferredAutoLogin) }
    var busy by remember { mutableStateOf(true) }
    var validSession by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf("正在检查登录状态…") }
    var detailIsError by remember { mutableStateOf(false) }
    var fieldError by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    var foreground by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true) }
    var gateOnly by remember { mutableStateOf(autoEnterValid) }
    var checkAttempt by remember { mutableStateOf(0) }
    val pausedSinceCheck = remember { booleanArrayOf(false) }
    val preferredSessionCheck = remember { UiSessionRequest() }
    fun clearInputError() {
        fieldError = false
        if (detailIsError) {
            detail = ""
            detailIsError = false
        }
    }
    fun cancelSessionChecks() { preferredSessionCheck.cancel() }
    fun checkSession(onResult: (String, Long) -> Unit) {
        if (!active || !foreground) return
        val ticket = preferredSessionCheck.begin()
        val callback: (String) -> Unit = { state ->
            preferredSessionCheck.complete(ticket) { if (active && foreground) onResult(state, ticket) }
        }
        if (sessionCheck != null) sessionCheck(app, callback)
        else if (AutoLogin.running) AutoLogin.login(app) { valid, _ ->
            callback(if (valid) "valid" else RuntimeStore(app).prefs.getString("session_state", "unknown").orEmpty())
        }
        else preferredSessionCheck.attach(ticket, SessionCoordinator.checkState(app, callback))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    pausedSinceCheck[0] = true
                    cancelSessionChecks()
                }
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    // A quick pause/resume can be coalesced before Compose observes `false`.
                    // An explicit new attempt guarantees a fresh check without duplicating entry.
                    if (pausedSinceCheck[0]) { pausedSinceCheck[0] = false; checkAttempt++ }
                }
                Lifecycle.Event.ON_DESTROY -> { foreground = false; cancelSessionChecks() }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            active = false
            password = ""
            cancelSessionChecks()
            lifecycle?.removeObserver(observer)
        }
    }
    fun checked(state: String) {
        if (!active || !foreground) return
        busy = false
        validSession = state == "valid"
        detailIsError = false
        fieldError = false
        if (validSession && autoEnterValid) {
            onAuthenticated()
            return
        }
        gateOnly = autoEnterValid && state != "expired"
        detail = when (state) {
            "valid" -> "当前登录仍有效，可以直接继续"
            "expired" -> "请登录学校图书馆账号"
            "vpn_login_required" -> "WebVPN 统一认证已过期，请在应用内登录并输入验证码"
            else -> "无法验证登录，请检查校园网络后重新检查，或使用学校网页登录"
        }
    }
    LaunchedEffect(checkAttempt, foreground) {
        if (!foreground) return@LaunchedEffect
        busy = true
        detail = "正在检查登录状态…"
        detailIsError = false
        fieldError = false
        checkSession { state, ticket ->
            if (active && state == "expired" && store.enabled && !store.paused) {
                detail = "登录已过期，正在自动重新登录…"
                AutoLogin.login(app) { valid, message ->
                    if (active && foreground && preferredSessionCheck.isCurrent(ticket)) {
                        busy = false
                        validSession = valid
                        detailIsError = !valid
                        fieldError = false
                        detail = if (valid) "自动重新登录成功，可以继续进入应用" else message
                        if (valid && autoEnterValid) onAuthenticated()
                        else gateOnly = false
                    }
                }
            } else checked(state)
        }
    }
    var schoolRoute by remember { mutableStateOf(LibraryRoute.DIRECT) }
    val schoolLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            validSession = true
            busy = false
            detailIsError = false
            fieldError = false
            detail = if (schoolRoute == LibraryRoute.WEBVPN)
                "WebVPN 应用内登录成功，可以继续"
            else "学校网页登录成功，可以继续；自动重新登录需在此填写账号密码"
            if (autoEnterValid) onAuthenticated()
        } else {
            checkAttempt++
        }
    }
    fun openSchoolLogin(route: LibraryRoute) {
        password = ""
        clearInputError()
        // A school-web login may switch accounts; old saved credentials cannot be assumed to match.
        store.setEnabled(false)
        store.setPreference(autoRelogin)
        schoolRoute = route
        schoolLogin.launch(Intent(context, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_WEBVPN, route == LibraryRoute.WEBVPN))
    }

    if (gateOnly) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
            contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) CircularProgressIndicator()
                Text(detail)
                if (!busy) Button(onClick = { checkAttempt++ }) { Text("重新检查") }
                TextButton(onClick = { openSchoolLogin(LibraryRoute.DIRECT) },
                    enabled = !busy) { Text("使用学校网页登录") }
                TextButton(onClick = { openSchoolLogin(LibraryRoute.WEBVPN) },
                    enabled = !busy) { Text("WebVPN 应用内登录") }
                LoginStatusCard(rememberLoginStatuses())
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Filled.Lock)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("图书馆预约", style = MaterialTheme.typography.titleLarge)
                    Text("直连优先 · WebVPN 备用", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("登录", style = MaterialTheme.typography.headlineSmall)
            Text("可连接校园网直连，或使用 WebVPN 校外登录", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(username, { username = it; clearInputError() }, label = { Text("学校账号") },
                singleLine = true, enabled = !busy, isError = fieldError,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; clearInputError() }, label = { Text("密码") },
                singleLine = true, enabled = !busy, isError = fieldError,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("默认密码是学号", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(detail, color = when {
                detailIsError -> MaterialTheme.colorScheme.error
                validSession -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
            Row(
                Modifier.fillMaxWidth().toggleable(autoRelogin, enabled = !busy, role = Role.Checkbox) {
                    autoRelogin = it
                    // Immediately honor opt-out, even if the user leaves without submitting.
                    if (!it) store.setEnabled(false) else store.setPreference(true)
                }, verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = autoRelogin, onCheckedChange = null, enabled = !busy)
                Text("自动重新登录")
            }
            Text(if (autoRelogin) "勾选后登录成功，将在本机加密保存账号密码。"
                else "不保存账号密码；登录过期后需要手动登录。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val error = LoginInput.error(username, password)
                if (error != null) { detail = error; detailIsError = true; fieldError = true; return@Button }
                if (AutoLogin.running) { detail = "已有登录正在进行，请稍后重试"; detailIsError = false; fieldError = false; return@Button }
                // Opt-out removes previously saved credentials as well as not storing new ones.
                if (!autoRelogin) {
                    runCatching { store.clear(); store.setPreference(false) }.onFailure {
                        detail = "无法删除已保存的登录信息，请重试"; detailIsError = true; fieldError = false; return@Button
                    }
                }
                val submittedUser = username
                val submittedPassword = password
                val rememberChoice = autoRelogin
                // Old-account credentials must not remain active if a new login/save fails.
                store.setEnabled(false)
                store.setPreference(rememberChoice)
                busy = true
                validSession = false
                detail = "正在登录并向学校验证…"
                detailIsError = false
                fieldError = false
                val ticket = preferredSessionCheck.begin()
                AutoLogin.loginWithCredentials(app, submittedUser, submittedPassword) { ok, message ->
                    // This explicit login may verify after the app is backgrounded. Honor its
                    // saved choice independently of UI lifetime, unless the user opted out since.
                    val persistence = if (ok && rememberChoice && store.preferredAutoLogin)
                        runCatching { store.save(submittedUser, submittedPassword) } else Result.success(Unit)
                    preferredSessionCheck.complete(ticket) {
                        if (active && foreground) {
                            busy = false
                            validSession = ok
                            detail = message
                            detailIsError = !ok
                            if (ok) {
                                password = ""
                                if (persistence.isSuccess) onAuthenticated()
                                else {
                                    detail = "已登录，但加密保存失败；可取消勾选后继续，本次不会自动重新登录"
                                    detailIsError = true
                                }
                            }
                        }
                    }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "请稍候…" else "登录并进入应用") }
            if (validSession) {
                Button(onClick = {
                    // Continuing a cookie session never enables unverified saved credentials.
                    // The checkbox applies to a credentials login, not to an account we cannot identify.
                    if (!autoRelogin) {
                        runCatching { store.clear(); store.setPreference(false) }.onFailure {
                            detail = "无法删除已保存的信息，请重试"; detailIsError = true; fieldError = false; return@Button
                        }
                    }
                    busy = true
                    // Do not trust an old result if the user leaves this screen open for a long time.
                    checkSession { state, _ ->
                        checked(state)
                        if (active && foreground && state == "valid" && !autoEnterValid) { password = ""; onAuthenticated() }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("继续进入应用") }
            }
            TextButton(onClick = { openSchoolLogin(LibraryRoute.DIRECT) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用学校网页登录") }
            TextButton(onClick = { openSchoolLogin(LibraryRoute.WEBVPN) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("WebVPN 应用内登录（夜间/校外备用）") }
            if (store.configured) TextButton(onClick = {
                runCatching { store.clear(); store.setPreference(autoRelogin) }.onSuccess {
                    username = ""; password = ""; detailIsError = false; fieldError = false
                    detail = "已删除保存的账号密码，当前网页会话不受影响"
                }.onFailure { detail = "删除失败，请重试"; detailIsError = true; fieldError = false }
            }, enabled = !busy) { Text("删除已保存的账号密码") }
            LoginStatusCard(rememberLoginStatuses(), onCheck = {
                SessionChecker.probe(app, LibraryRoute.DIRECT, updateSession = false) {}
            })
            Text("直连图书馆使用 HTTP，请仅在可信校园网络中使用；WebVPN 使用 HTTPS。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Owns a foreground request and rejects callbacks from an earlier lifecycle/check generation. */
internal class UiSessionRequest {
    private var generation = 0L
    private var handle: AutoCloseable? = null
    var pending = false
        private set

    fun begin(): Long {
        cancel()
        pending = true
        return generation
    }

    fun isCurrent(ticket: Long) = ticket == generation

    fun attach(ticket: Long, value: AutoCloseable) {
        if (!isCurrent(ticket) || !pending) value.close()
        else { handle?.close(); handle = value }
    }

    fun complete(ticket: Long, action: () -> Unit) {
        if (!isCurrent(ticket) || !pending) return
        pending = false
        val old = handle
        handle = null
        old?.close()
        action()
    }

    fun cancel() {
        generation++
        pending = false
        val old = handle
        handle = null
        old?.close()
    }
}
