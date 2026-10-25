package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A settings-only page. Opening it never requests a permission or starts a test. */
@Composable
fun PermissionsPage(
    exact: Boolean,
    notifications: Boolean,
    battery: Boolean,
    testDetail: String,
    message: String = "",
    onExactAlarm: () -> Unit,
    onNotifications: () -> Unit,
    onAppSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onPermissionGuide: () -> Unit,
    onWakeTest: () -> Unit,
    profile: OemProfile = OemProfiles.current(),
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {}
) {
    var advanced by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().testTag("permissions-page")
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("权限设置", style = MaterialTheme.typography.headlineSmall)
        Text("定时预约依赖精确闹钟和通知；后台限制因厂商而异，已按本机品牌显示设置路径，返回应用后会自动刷新状态。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(message, Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        AppCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SectionHeader("必要授权", "缺少任一项时，计划可能无法唤醒或提醒",
                modifier = Modifier.padding(bottom = 6.dp))
            PermissionRow(
                icon = Icons.Filled.DateRange,
                title = "精确闹钟",
                status = if (exact) "已允许" else "未允许 · 需开启",
                tone = if (exact) StatusTone.Success else StatusTone.Error,
                action = if (exact) "去设置" else "去开启",
                tag = "permission-exact",
                onAction = onExactAlarm
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            PermissionRow(
                icon = Icons.Filled.Notifications,
                title = "通知",
                status = if (notifications) "已允许" else "未允许",
                tone = if (notifications) StatusTone.Success else StatusTone.Warning,
                action = if (notifications) "去设置" else "去开启",
                tag = "permission-notifications",
                onAction = onNotifications
            )
        }
        AppCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SectionHeader("后台运行建议",
                if (profile.family == OemFamily.GENERIC) "未识别品牌，以下为通用设置步骤"
                else "当前设备：${profile.displayName}",
                modifier = Modifier.padding(bottom = 6.dp))
            PermissionRow(
                icon = Icons.Filled.Settings,
                title = "电池优化",
                status = if (battery) "已开启" else "未开启",
                tone = if (battery) StatusTone.Success else StatusTone.Warning,
                action = "查看省电设置",
                tag = "permission-battery",
                onAction = onBatterySettings
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            PermissionRow(
                icon = Icons.Filled.Settings,
                title = "自启动与后台限制",
                status = "需手动确认",
                tone = StatusTone.Neutral,
                action = "打开应用设置",
                tag = "permission-autostart",
                onAction = onAppSettings
            )
            OemGuidance(profile)
        }
        AppCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().testTag("advanced-toggle").clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(Icons.Filled.Build, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("高级诊断", style = MaterialTheme.typography.titleMedium)
                    Text("唤醒测试、权限引导与详细说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (advanced) "收起高级诊断" else "展开高级诊断",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (advanced) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Text("后台唤醒测试只验证锁屏后的闹钟和后台服务，不会提交预约，也不更改正式任务。请避开放号前 20 分钟，以免连续闹钟受到系统限制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = onWakeTest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("permission-wake-test")
                ) { Text("60 秒后台唤醒测试（不预约）") }
                Text("测试状态", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(testDetail, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onPermissionGuide, modifier = Modifier.fillMaxWidth()) {
                    Text("重新查看权限引导")
                }
            }
        }
        footer()
    }
}

@Composable
private fun OemGuidance(profile: OemProfile) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("oem-guidance"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台设置步骤", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            profile.steps.forEachIndexed { index, step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("页面名称会随系统版本变化；完成设置后返回应用刷新状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    status: String,
    tone: StatusTone,
    action: String,
    tag: String,
    onAction: () -> Unit,
    detail: String? = null
) {
    val stackAction = LocalDensity.current.fontScale >= 1.3f
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (stackAction) 8.dp else 0.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.labelMedium, color = toneColor(tone))
            }
            if (!stackAction) PermissionAction(action, tag, onAction)
        }
        if (stackAction) PermissionAction(action, tag, onAction, Modifier.fillMaxWidth())
    }
    if (detail != null) {
        Text(detail, Modifier.padding(start = 48.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionAction(
    action: String,
    tag: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onAction,
        modifier = modifier.heightIn(min = 40.dp).testTag(tag)
    ) {
        Text(action, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Success -> MaterialTheme.colorScheme.primary
    StatusTone.Warning -> MaterialTheme.colorScheme.tertiary
    StatusTone.Error -> MaterialTheme.colorScheme.error
    StatusTone.Info -> MaterialTheme.colorScheme.secondary
    StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}
