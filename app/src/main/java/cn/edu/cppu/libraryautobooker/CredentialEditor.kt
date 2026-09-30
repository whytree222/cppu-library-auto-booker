package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.CredentialStore

@Composable
fun CredentialEditor(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CredentialStore(context) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDone, title = { Text("自动重新登录") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("填写后将启用自动重新登录。账号密码仅加密保存在这台手机，可随时删除。学校登录使用 HTTP，请在可信校园网络下使用。")
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("学校账号") }, singleLine = true)
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (error.isNotEmpty()) Text(error)
            if (store.configured) TextButton(onClick = {
                AutoLogin.cancel(context)
                runCatching { store.clear() }.onSuccess { password = ""; username = ""; onDone() }
                    .onFailure { error = "删除失败，请重试" }
            }) { Text("删除保存的账号密码") }
        }
    }, confirmButton = { TextButton(onClick = {
        if (username.isBlank() || password.isEmpty()) { error = "请填写账号和密码"; return@TextButton }
        if ((username + password).any { it == '\n' || it == '\r' }) { error = "账号密码不能包含换行，请检查复制的内容"; return@TextButton }
        AutoLogin.cancel(context)
        runCatching { store.save(username, password) }.onSuccess {
            password = ""; username = ""; onDone()
            AutoLogin.login(context, force = true)
        }.onFailure { error = "无法加密保存，请重试；原有信息未更改" }
    }) { Text("保存并验证登录") } }, dismissButton = { TextButton(onClick = onDone) { Text("取消") } })
}

