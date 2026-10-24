package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

internal data class DirectBookingFeedback(val title: String, val detail: String) {
    companion object {
        fun from(record: RuntimeStore.RunRecord): DirectBookingFeedback? {
            if (!record.id.startsWith("manual:") || record.state !in setOf("DONE", "FAILED", "INTERRUPTED")) return null
            val detail = record.lines.lastOrNull().orEmpty().ifBlank { "未取得明确结果，请到学校系统核对预约记录。" }
            val title = when {
                record.state == "INTERRUPTED" -> "预约结果待核对"
                record.state == "DONE" -> "直接预约已完成"
                Regex("未确认|无法确认|未收到|结果不明|核对学校|超时").containsMatchIn(detail) -> "预约结果待核对"
                else -> "直接预约未完成"
            }
            return DirectBookingFeedback(title, detail)
        }
    }
}

@Composable
internal fun DirectBookingFeedbackDialog(title: String, detail: String, onDismiss: () -> Unit) {
    val warning = "待核对" in title
    val error = !warning && ("未完成" in title || "未启动" in title || "检查" in title)
    val color = when {
        error -> MaterialTheme.colorScheme.error
        warning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                when {
                    error -> Icons.Filled.Warning
                    warning -> Icons.Filled.Info
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = color
            )
        },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(detail, style = MaterialTheme.typography.bodyMedium) }
                Text("详细步骤保留在“记录”页。关闭弹窗不会重新提交预约。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
