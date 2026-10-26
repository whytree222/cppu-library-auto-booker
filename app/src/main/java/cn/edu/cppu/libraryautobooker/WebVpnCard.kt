package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

@Composable
fun WebVpnCard(
    statuses: LoginStatusStore.Snapshot,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onCheck: () -> Unit,
    onAccounts: () -> Unit
) {
    val (badge, tone) = loginPill(statuses.webVpn.state)
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Filled.Lock)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("WebVPN 校外备用", style = MaterialTheme.typography.titleMedium)
                Text("夜间或校外校园网中断时使用学校 WebVPN",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(badge, tone)
        }
        Text("最近检查：${RuntimeStore.format(statuses.webVpn.checkedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onEnabledChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("直连失败时自动切换")
                Text("仅在预约提交前发生网络故障时切换；提交后不会换线路重发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(statuses.webVpn.detail, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("WebVPN 应用内登录") }
        FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("检查 WebVPN") }
        OutlinedButton(onClick = onAccounts, modifier = Modifier.fillMaxWidth()) { Text("WebVPN 账号设置") }
        Text("统一认证过期后，请在应用内手动输入验证码。可在本机加密记住两套账号；已有 WebVPN 会话有效时继续后台预约。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
