package cn.edu.cppu.libraryautobooker

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

@Composable
fun rememberLoginStatuses(): LoginStatusStore.Snapshot {
    val context = LocalContext.current
    val store = remember(context) { LoginStatusStore(context) }
    var snapshot by remember(store) { mutableStateOf(store.snapshot()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("login_") == true) snapshot = store.snapshot()
        }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return snapshot
}

@Composable
fun StatusLine(label: String, state: String, detail: String, checkedAt: Long? = null,
               statusText: String? = null, showDetails: Boolean = true) {
    val color = when (state) {
        "valid" -> Color(0xFF176B4D)
        "expired", "blocked" -> Color(0xFFB3261E)
        "checking", "warning" -> Color(0xFF875500)
        else -> Color(0xFF536171)
    }
    val badge = statusText ?: when (state) {
        "valid" -> "有效 · 最近检查"
        "expired" -> "未登录 / 已过期"
        "checking" -> "检查中"
        else -> "无法确认"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Surface(color = color.copy(alpha = 0.10f), shape = MaterialTheme.shapes.small) {
            Text(badge, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = color,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        if (showDetails) Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475467))
        if (checkedAt != null) Text(if (checkedAt <= 0) "尚无完成检查记录" else "最近检查：${RuntimeStore.format(checkedAt)}",
            style = MaterialTheme.typography.labelSmall, color = Color(0xFF667085))
    }
}

@Composable
fun LoginStatusCard(statuses: LoginStatusStore.Snapshot, onCheck: (() -> Unit)? = null, hasActions: Boolean = false,
                    content: @Composable ColumnScope.() -> Unit = {}) {
    var details by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F5FC), contentColor = Color(0xFF223047))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("登录状态", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = Color(0xFF245A8D))
            StatusLine("校园网直连 · 图书馆", statuses.direct.state, statuses.direct.detail, statuses.direct.checkedAt, showDetails = details)
            Text("需要连接可访问图书馆的校园网络；状态仅代表最近检查结果。",
                style = MaterialTheme.typography.bodySmall)
            if (onCheck != null) OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("检查登录状态") }
            TextButton(onClick = { details = !details }) { Text(if (details) "收起登录详情" else "查看登录详情") }
            if (hasActions) TextButton(onClick = { actions = !actions }) { Text(if (actions) "收起登录操作" else "登录操作 / 连接设置") }
            if (actions) content()
        }
    }
}

@Composable
fun PermissionStatusCard(exact: Boolean, notifications: Boolean, battery: Boolean,
                         content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F3FA), contentColor = Color(0xFF223047))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("权限与后台运行", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = Color(0xFF67418C))
            StatusLine("闹钟和提醒 · 定时任务必需", if (exact) "valid" else "blocked",
                "用于登记精确闹钟；已允许也不能保证每次秒级准点。", statusText = if (exact) "已允许" else "未允许 · 需开启")
            StatusLine("结果通知 · 建议开启", if (notifications) "valid" else "warning",
                "用于显示后台任务和预约结果。", statusText = if (notifications) "已允许" else "未允许")
            StatusLine("系统省电豁免 · 建议检查", if (battery) "valid" else "warning",
                "影响锁屏时后台运行；不是已允许网络访问的保证。", statusText = if (battery) "已开启" else "未开启")
            StatusLine("自启动与厂商后台限制", "unknown", "Android 没有通用接口确认小米自启动或无限制策略，请到系统应用设置手动核对。",
                statusText = "需手动确认")
            content()
        }
    }
}
