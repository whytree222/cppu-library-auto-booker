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
        OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("WebVPN 网页登录") }
        FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("检查 WebVPN") }
        OutlinedButton(onClick = onAccounts, modifier = Modifier.fillMaxWidth()) { Text("自动登录账号设置") }
        Text("可选择在本机加密保存统一认证与图书馆账号，并在会话过期时自动重登；验证码只在本机识别，不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
