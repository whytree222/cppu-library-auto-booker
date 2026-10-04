package cn.edu.cppu.libraryautobooker

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun ReleaseTimeField(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<TimePickerDialog?>(null) }
    var manual by remember { mutableStateOf(false) }
    var hourText by remember { mutableStateOf("") }
    var minuteText by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("每天放号时间", style = MaterialTheme.typography.titleMedium)
            Text(String.format(Locale.ROOT, "%02d:%02d", hour, minute), style = MaterialTheme.typography.headlineMedium)
            Text("24 小时制，设置后点击“保存并安排任务”。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    picker?.dismiss()
                    picker = TimePickerDialog(context, { _, selectedHour, selectedMinute ->
                        onChange(selectedHour, selectedMinute)
                    }, hour, minute, true).apply { show() }
                }, modifier = Modifier.weight(1f)) { Text("选择时间") }
                OutlinedButton(onClick = {
                    hourText = hour.toString().padStart(2, '0')
                    minuteText = minute.toString().padStart(2, '0')
                    manual = true
                }, modifier = Modifier.weight(1f)) { Text("手动填写") }
            }
        }
    }

    if (manual) {
        val enteredHour = hourText.toIntOrNull()
        val enteredMinute = minuteText.toIntOrNull()
        val valid = enteredHour != null && enteredHour in 0..23 && enteredMinute != null && enteredMinute in 0..59
        AlertDialog(onDismissRequest = { manual = false }, title = { Text("填写放号时间") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hourText, { hourText = it.filter { char -> char in '0'..'9' }.take(2) },
                        label = { Text("小时") }, supportingText = { Text("00–23") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(minuteText, { minuteText = it.filter { char -> char in '0'..'9' }.take(2) },
                        label = { Text("分钟") }, supportingText = { Text("00–59") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                if (!valid) Text("请填写有效的小时和分钟", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (valid) { onChange(requireNotNull(enteredHour), requireNotNull(enteredMinute)); manual = false }
            }, enabled = valid) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } })
    }
}
