package cn.edu.cppu.libraryautobooker

import android.app.Activity
import android.content.Intent
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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

/** The entry page contains only login controls, not the reservation dashboard. */
@Composable
fun EntryLoginScreen(
    sessionCheck: (Context, (String) -> Unit) -> Unit = { context, callback -> SessionChecker.checkDetailed(context, callback) },
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
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false; password = "" } }

    fun checked(state: String) {
        if (!active) return
        busy = false
        validSession = state == "valid"
        detail = when (state) {
            "valid" -> "当前登录仍有效，可以直接继续"
            "expired" -> "请登录学校图书馆账号"
            else -> "无法验证登录，请检查校园网络后重试"
        }
    }
    LaunchedEffect(Unit) {
        if (AutoLogin.running) AutoLogin.login(app) { valid, _ -> checked(if (valid) "valid" else "expired") }
        else sessionCheck(app) { state ->
            if (active && state == "expired" && store.enabled && !store.paused) {
                detail = "登录已过期，正在自动重新登录…"
                AutoLogin.login(app) { valid, message ->
                    if (active) {
                        busy = false
                        validSession = valid
                        detail = if (valid) "自动重新登录成功，可以继续进入应用" else message
                    }
                }
            } else checked(state)
        }
    }
    val schoolLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            validSession = true
            busy = false
            detail = "学校网页登录成功，可以继续；自动重新登录需在此填写账号密码"
        } else {
            busy = true
            sessionCheck(app, ::checked)
        }
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("图书馆自动预约", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("登录", style = MaterialTheme.typography.headlineLarge)
            Text("连接校园网络后登录", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(username, { username = it }, label = { Text("学校账号") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("密码") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
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
            Text(detail, color = if (validSession) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val error = LoginInput.error(username, password)
                if (error != null) { detail = error; return@Button }
                if (AutoLogin.running) { detail = "已有登录正在进行，请稍后重试"; return@Button }
                // Opt-out removes previously saved credentials as well as not storing new ones.
                if (!autoRelogin) {
                    runCatching { store.clear(); store.setPreference(false) }.onFailure {
                        detail = "无法删除已保存的登录信息，请重试"; return@Button
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
                AutoLogin.loginWithCredentials(app, submittedUser, submittedPassword) { ok, message ->
                    val persistence = if (ok && rememberChoice) runCatching { store.save(submittedUser, submittedPassword) }
                        else Result.success(Unit)
                    if (active) {
                        busy = false
                        validSession = ok
                        detail = message
                        if (ok) {
                            password = ""
                            if (persistence.isSuccess) onAuthenticated()
                            else detail = "已登录，但加密保存失败；可取消勾选后继续，本次不会自动重新登录"
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
                            detail = "无法删除已保存的信息，请重试"; return@Button
                        }
                    }
                    busy = true
                    // Do not trust an old result if the user leaves this screen open for a long time.
                    sessionCheck(app) { state ->
                        checked(state)
                        if (active && state == "valid") { password = ""; onAuthenticated() }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("继续进入应用") }
            }
            TextButton(onClick = {
                password = ""
                // A school-web login may switch accounts; old saved credentials cannot be assumed to match.
                store.setEnabled(false)
                store.setPreference(autoRelogin)
                schoolLogin.launch(Intent(context, LoginActivity::class.java))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用学校网页登录") }
            if (store.configured) TextButton(onClick = {
                runCatching { store.clear(); store.setPreference(autoRelogin) }.onSuccess {
                    username = ""; password = ""; detail = "已删除保存的账号密码，当前网页会话不受影响"
                }.onFailure { detail = "删除失败，请重试" }
            }, enabled = !busy) { Text("删除已保存的账号密码") }
            Text("学校使用 HTTP，请仅在可信校园网络中登录。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
