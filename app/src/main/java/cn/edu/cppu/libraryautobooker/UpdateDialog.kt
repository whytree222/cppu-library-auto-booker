package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.update.UpdateManifest
import cn.edu.cppu.libraryautobooker.update.UpdateState

@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    currentVersionCode: Int,
    safe: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: () -> Unit
) {
    val manifest: UpdateManifest = when (state) {
        is UpdateState.Available -> state.manifest
        is UpdateState.Downloading -> state.manifest
        is UpdateState.Ready -> state.manifest
        is UpdateState.Failed -> state.manifest ?: return
        else -> return
    }
    val title = when (state) {
        is UpdateState.Available -> "发现新版本 v${manifest.versionName}"
        is UpdateState.Downloading -> "正在下载更新"
        is UpdateState.Ready -> "更新已准备就绪"
        is UpdateState.Failed -> "更新未完成"
        else -> return
    }
    val required = manifest.updateRequired(currentVersionCode)
    AlertDialog(
        onDismissRequest = { if (state !is UpdateState.Downloading) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("当前版本 $currentVersion → ${manifest.versionName}",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(formatUpdateSize(manifest.size), StatusTone.Neutral)
                    if (required) StatusPill("必要更新", StatusTone.Warning)
                }
                if (!safe) {
                    Text("预约任务运行中或将在 15 分钟内触发，下载和安装已暂停。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                when (state) {
                    is UpdateState.Available -> {
                        SelectionContainer {
                            Text(manifest.notes.ifBlank { "本次更新没有附加说明。" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { downloadFraction(state.downloaded, state.total) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${formatUpdateSize(state.downloaded)} / ${formatUpdateSize(state.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.Ready -> {
                        Text("已通过 SHA-256、包名、版本和签名校验。系统安装器会再次确认覆盖安装，应用无法静默安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.Failed -> {
                        Text(state.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Unit
                }
                if (required) {
                    Text("此版本为必要更新；为了不影响预约，临近 00:58 任务时会自动延后。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(
                    onClick = onDownload,
                    enabled = safe,
                    modifier = Modifier.testTag("update-download")
                ) { Text("下载更新") }
                is UpdateState.Downloading -> TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.testTag("update-cancel")
                ) { Text("取消下载") }
                is UpdateState.Ready -> Button(
                    onClick = onInstall,
                    enabled = safe,
                    modifier = Modifier.testTag("update-install")
                ) { Text("安装更新") }
                is UpdateState.Failed -> Button(
                    onClick = onRetry,
                    enabled = safe,
                    modifier = Modifier.testTag("update-download")
                ) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is UpdateState.Downloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenPage, modifier = Modifier.testTag("update-open-page")) {
                        Text("发布页")
                    }
                    TextButton(onClick = onDismiss) {
                        Text(if (state is UpdateState.Available || state is UpdateState.Ready) "稍后" else "关闭")
                    }
                }
            }
        }
    )
}
