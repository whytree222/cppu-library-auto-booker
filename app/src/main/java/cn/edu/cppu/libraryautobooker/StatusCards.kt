package cn.edu.cppu.libraryautobooker

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

internal fun loginPill(state: String): Pair<String, StatusTone> = when (state) {
    "valid" -> "登录有效" to StatusTone.Success
    "expired" -> "未登录 / 已过期" to StatusTone.Error
    "blocked" -> "登录受限" to StatusTone.Error
    "vpn_login_required" -> "需网页登录" to StatusTone.Warning
    "checking" -> "检查中" to StatusTone.Info
    else -> "无法确认" to StatusTone.Neutral
}

@Composable
fun LoginStatusCard(
    statuses: LoginStatusStore.Snapshot,
    onCheck: (() -> Unit)? = null,
    hasActions: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    var details by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    val (badge, tone) = loginPill(statuses.direct.state)
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Filled.Lock)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("登录状态", style = MaterialTheme.typography.titleMedium)
                Text("校园网直连 · 图书馆", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(badge, tone)
        }
        Text("最近检查：${RuntimeStore.format(statuses.direct.checkedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCheck != null) {
                if (statuses.direct.state != "valid") {
                    FilledTonalButton(onClick = onCheck, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("检查登录")
                    }
                } else {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("检查登录")
                    }
                }
            }
            TextButton(onClick = { details = !details }) {
                Text(if (details) "收起登录详情" else "查看登录详情")
            }
        }
        if (details) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Text(statuses.direct.detail, style = MaterialTheme.typography.bodyMedium)
            Text("需要连接可访问图书馆的校园网络；校园网不可用时可在下方使用 WebVPN 备用。状态仅代表最近一次检查结果。图书馆站点使用 HTTP，请仅在可信网络中使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasActions) {
            TextButton(onClick = { actions = !actions }) {
                Text(if (actions) "收起登录操作" else "登录操作 / 连接设置")
            }
            if (actions) content()
        }
    }
}
