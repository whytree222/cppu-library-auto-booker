package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.update.UpdateState
import java.util.Locale

@Composable
fun UpdateCard(
    state: UpdateState,
    currentVersion: String,
    safe: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is UpdateState.Unavailable) return
    val stacked = LocalDensity.current.fontScale >= 1.3f
    AppCard(modifier = modifier.testTag("update-card")) {
        SectionHeader("应用更新", "当前版本 $currentVersion")
        if (!safe) {
            Text(
                "预约任务运行中或将在 15 分钟内触发，更新操作已暂停。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        when (state) {
            UpdateState.Unavailable -> Unit
            UpdateState.Idle -> {
                Text(
                    "打开应用时自动检查更新，每天最多一次；不会自动下载或安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UpdateActions(
                    stacked = stacked,
                    actions = listOf(
                        UpdateAction("检查更新", "update-check", primary = false, enabled = safe, onClick = onCheck)
                    )
                )
            }
            UpdateState.Checking -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在检查更新…", style = MaterialTheme.typography.bodyMedium)
            }
            UpdateState.UpToDate -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("已是最新", StatusTone.Success)
                    Text("当前已安装最新版本。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                UpdateActions(
                    stacked = stacked,
                    actions = listOf(
                        UpdateAction("再次检查", "update-check", primary = false, enabled = safe, onClick = onCheck)
                    )
                )
            }
            is UpdateState.Available -> {
                UpdateVersionHeader(state)
                ExpandableText(state.manifest.notes.ifBlank { "本次更新没有附加说明。" },
                    collapsedMaxLines = 4)
                UpdateActions(
                    stacked = stacked,
                    actions = listOf(
                        UpdateAction("下载更新", "update-download", primary = true, enabled = safe,
                            onClick = onDownload),
                        UpdateAction("打开发布页", "update-open-page", primary = false,
                            onClick = onOpenPage)
                    )
                )
            }
            is UpdateState.Downloading -> {
                Text("正在下载 ${state.manifest.versionName}…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { downloadFraction(state.downloaded, state.total) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${formatUpdateSize(state.downloaded)} / ${formatUpdateSize(state.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UpdateActions(
                    stacked = stacked,
                    actions = listOf(
                        UpdateAction("取消下载", "update-cancel", primary = false,
                            onClick = onCancelDownload)
                    )
                )
            }
            is UpdateState.Ready -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("已下载并通过校验", StatusTone.Success)
                    Text(formatUpdateSize(state.manifest.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "已校验 SHA-256、包名、版本和签名；系统会再次确认覆盖安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UpdateActions(
                    stacked = stacked,
                    actions = listOf(
                        UpdateAction("安装更新", "update-install", primary = true, enabled = safe,
                            onClick = onInstall),
                        UpdateAction("打开发布页", "update-open-page", primary = false,
                            onClick = onOpenPage)
                    )
                )
            }
            is UpdateState.Failed -> {
                StatusPill("更新失败", StatusTone.Error)
                Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                UpdateActions(
                    stacked = stacked,
                    actions = buildList {
                        if (state.manifest != null) {
                            add(UpdateAction("重新下载", "update-download", primary = true,
                                enabled = safe, onClick = onDownload))
                        } else {
                            add(UpdateAction("重试检查", "update-check", primary = true,
                                enabled = safe, onClick = onCheck))
                        }
                        add(UpdateAction("打开发布页", "update-open-page", primary = false,
                            onClick = onOpenPage))
                    }
                )
            }
        }
    }
}

@Composable
private fun UpdateVersionHeader(state: UpdateState.Available) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StatusPill("可更新 v${state.manifest.versionName}", StatusTone.Info)
        if (state.required) StatusPill("必要更新", StatusTone.Warning)
        Text(formatUpdateSize(state.manifest.size), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class UpdateAction(
    val label: String,
    val tag: String,
    val primary: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
private fun UpdateActions(stacked: Boolean, actions: List<UpdateAction>) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action -> UpdateActionButton(action, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action -> UpdateActionButton(action, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun UpdateActionButton(action: UpdateAction, modifier: Modifier) {
    if (action.primary) {
        Button(onClick = action.onClick, enabled = action.enabled,
            modifier = modifier.testTag(action.tag)) {
            Text(action.label)
        }
    } else {
        OutlinedButton(onClick = action.onClick, enabled = action.enabled,
            modifier = modifier.testTag(action.tag)) {
            Text(action.label)
        }
    }
}

fun formatUpdateSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun downloadFraction(downloaded: Long, total: Long): Float =
    if (total <= 0) 0f else (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
