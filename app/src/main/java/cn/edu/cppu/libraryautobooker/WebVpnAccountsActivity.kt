package cn.edu.cppu.libraryautobooker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind

/** Opt-in account settings only. Passwords are never carried in Activity intents. */
class WebVpnAccountsActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var message by remember { mutableStateOf("") }
            val sso = remember(revision) { accountState(Kind.SSO) }
            val library = remember(revision) { accountState(Kind.LIBRARY) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    WebVpnAccountsScreen(sso, library, message,
                        onSave = { kind, username, password ->
                            val error = WebVpnCredentialStore.inputError(username, password)
                            if (error != null) {
                                message = error
                                false
                            } else {
                                val saved = runCatching {
                                    WebVpnCredentialStore(this, kind).save(username, password)
                                }.isSuccess
                                message = if (saved) "已在本机加密保存；待学校服务器验证，不代表已经登录。"
                                    else "无法安全保存账号，请重试或使用网页登录。"
                                revision++
                                saved
                            }
                        },
                        onEnabled = { kind, enabled ->
                            WebVpnCredentialStore(this, kind).setEnabled(enabled)
                            message = if (enabled) "已允许使用这个账号自动登录。" else "已关闭这个账号的自动登录。"
                            revision++
                        },
                        onResume = { kind ->
                            WebVpnCredentialStore(this, kind).resume()
                            message = "已由你允许重新尝试；请先在前台验证登录。"
                            revision++
                        },
                        onClear = { kind ->
                            val cleared = runCatching { WebVpnCredentialStore(this, kind).clear() }.isSuccess
                            message = if (cleared) "此账号的本机加密凭据已删除；学校已有登录会话不会因此退出。"
                                else "删除未完成，请重试。"
                            revision++
                        },
                        onVerify = {
                            startActivity(Intent(this, LoginActivity::class.java)
                                .putExtra(LoginActivity.EXTRA_WEBVPN, true))
                        },
                        onClose = ::finish)
                }
            }
        }
    }

    private fun accountState(kind: Kind): WebVpnAccountSettings {
        val store = WebVpnCredentialStore(this, kind)
        return WebVpnAccountSettings(store.savedUsername(), store.configured, store.enabled, store.paused, store.verified)
    }

    override fun onResume() {
        super.onResume()
        revision++
    }
}

internal data class WebVpnAccountSettings(
    val username: String = "",
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val verified: Boolean = false
)

@Composable
internal fun WebVpnAccountsScreen(
    sso: WebVpnAccountSettings,
    library: WebVpnAccountSettings,
    message: String,
    onSave: (Kind, String, String) -> Boolean,
    onEnabled: (Kind, Boolean) -> Unit,
    onResume: (Kind) -> Unit,
    onClear: (Kind) -> Unit,
    onVerify: () -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("WebVPN 自动登录账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("学校统一认证和图书馆是两层登录，请分别设置。不会从校园网直连账号中自动复制密码。")
        Text("只有你明确保存并开启后，应用才会使用该账号正常登录。账号和密码仅在本机加密保存；验证码在本机识别，不上传第三方。",
            style = MaterialTheme.typography.bodySmall)
        WebVpnAccountCard(Kind.SSO, sso, onSave, onEnabled, onResume, onClear)
        WebVpnAccountCard(Kind.LIBRARY, library, onSave, onEnabled, onResume, onClear)
        if (message.isNotEmpty()) Text(message, fontWeight = FontWeight.SemiBold)
        Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) { Text("打开 WebVPN 验证两层登录") }
        Text("先完成学校统一认证，再登录代理内的图书馆。若学校要求短信等额外验证或识别失败，请在网页手动完成。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回应用") }
    }
}

@Composable
private fun WebVpnAccountCard(
    kind: Kind,
    state: WebVpnAccountSettings,
    onSave: (Kind, String, String) -> Boolean,
    onEnabled: (Kind, Boolean) -> Unit,
    onResume: (Kind) -> Unit,
    onClear: (Kind) -> Unit
) {
    var username by remember(kind, state.username) { mutableStateOf(state.username) }
    // Deliberately not rememberSaveable: passwords must not enter Bundles or saved state.
    var password by remember(kind) { mutableStateOf("") }
    var consent by remember(kind) { mutableStateOf(false) }
    val isSso = kind == Kind.SSO
    val label = if (isSso) "统一认证" else "图书馆"
    Card(colors = CardDefaults.cardColors(
        containerColor = if (isSso) Color(0xFFF0F5FC) else Color(0xFFF4F0FA),
        contentColor = Color(0xFF223047))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isSso) "① 学校统一认证账号" else "② WebVPN 内图书馆账号",
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(if (isSso) "填写学校统一身份认证平台的学工号和密码，不是图书馆密码。"
                else "填写图书馆系统账号和密码，与上面的统一认证密码独立保存。")
            Text(when {
                !state.configured -> "未保存 · 不会自动填写账号或密码"
                state.paused -> "已暂停 · 登录失败后需你确认才能重新尝试"
                !state.enabled -> "已保存 · 自动登录已关闭"
                state.verified -> "已保存 · 上次验证通过（不代表当前会话仍有效）"
                else -> "已保存并开启 · 待学校服务器验证"
            }, color = if (state.paused) Color(0xFFB3261E) else Color(0xFF245A8D), fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = username, onValueChange = { username = it },
                label = { Text(if (isSso) "统一认证学工号" else "WebVPN 图书馆账号") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it },
                label = { Text(if (isSso) "统一认证密码" else "WebVPN 图书馆密码") },
                supportingText = { Text(if (state.configured) "已保存密码不会回显；修改时请重新输入。" else "密码只在本机加密保存。") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it })
                Text("我确认使用本人账号，并允许应用保存及自动登录${label}；不用于盈利或攻击。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                if (onSave(kind, username, password)) {
                    password = ""
                    consent = false
                }
            }, enabled = consent && WebVpnCredentialStore.inputError(username, password) == null,
                modifier = Modifier.fillMaxWidth()) {
                Text("保存并开启${label}自动登录")
            }
            if (state.configured) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = state.enabled, onCheckedChange = { onEnabled(kind, it) })
                    Text("允许${label}自动登录")
                }
                if (state.enabled && state.paused) OutlinedButton(onClick = { onResume(kind) }) {
                    Text("允许${label}重新尝试")
                }
                OutlinedButton(onClick = {
                    password = ""
                    consent = false
                    onClear(kind)
                }) { Text("删除本机${label}账号") }
            }
        }
    }
}
