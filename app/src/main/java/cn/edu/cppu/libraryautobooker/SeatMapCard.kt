package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.RoomSeatMap

/** A click edits only the draft. The existing explicit run/save actions submit or schedule. */
internal fun toggleMapSeat(map: RoomSeatMap, row: Int, column: Int, selected: List<String>): List<String> {
    val seat = map.seats.singleOrNull { it.row == row && it.column == column } ?: return selected
    if (map.issue(seat) != null) return selected
    return when {
        seat.id in selected -> selected - seat.id
        selected.size < 10 -> selected + seat.id
        else -> selected
    }
}

@Composable
fun SeatMapCard(map: RoomSeatMap?, selected: List<String>, onChange: (List<String>) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("点击座位图选座", style = MaterialTheme.typography.titleMedium)
            if (map == null) {
                Text("此阅览室尚无已核对的座位图，不能猜测座位编号。请重新同步或等待补充布局。",
                    color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text("${map.room.groupName} · ${map.room.name} · ${map.seats.size} 个座位标记")
            Text("点击加入候选顺序，再点可取消；最多 10 个，只会按顺序预约其中一个。地图可上下、左右滑动。",
                style = MaterialTheme.typography.bodySmall)
            Text("布局采集于 ${map.capturedDate}，不表示实时余座。高亮仅表示你的选择；运行时会重新核对学校状态。",
                style = MaterialTheme.typography.bodySmall)
            var message by remember(map.room.id) { mutableStateOf("") }
            key(map.room.id) {
                val byPosition = remember(map) { map.seats.associateBy { it.row to it.column } }
                Box(Modifier.fillMaxWidth().height(370.dp).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                    Column {
                        repeat(map.rows) { row ->
                            Row {
                                repeat(map.columns) { column ->
                                    val seat = byPosition[row to column]
                                    Box(Modifier.size(width = 76.dp, height = 54.dp).padding(2.dp)) {
                                        if (seat != null) {
                                            val issue = map.issue(seat)
                                            val description = "${seat.id.ifBlank { "无编号" }}，第 ${row + 1} 行第 ${column + 1} 列" +
                                                if (issue != null) "，$issue，不可选择" else if (seat.id in selected) "，已选，再点取消" else "，点击加入候选"
                                            val modifier = Modifier.size(width = 72.dp, height = 50.dp).semantics { contentDescription = description }
                                            val click = {
                                                if (selected.size >= 10 && seat.id !in selected) message = "最多选择 10 个候选座位，请先移除一个"
                                                else { message = ""; onChange(toggleMapSeat(map, row, column, selected)) }
                                            }
                                            if (issue == null && seat.id in selected) {
                                                Button(onClick = click, modifier = modifier, contentPadding = PaddingValues(2.dp)) {
                                                    Text(seat.id, style = MaterialTheme.typography.labelSmall)
                                                }
                                            } else {
                                                OutlinedButton(onClick = click, enabled = issue == null, modifier = modifier,
                                                    contentPadding = PaddingValues(2.dp),
                                                    border = BorderStroke(1.dp, if (issue == null) MaterialTheme.colorScheme.outline
                                                        else MaterialTheme.colorScheme.error)) {
                                                    Text(if (issue != null) if (seat.id.length > 8) "⚠ 异常" else "⚠\n${seat.id.ifBlank { "无编号" }}" else seat.id,
                                                        style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val issueCount = remember(map) { map.seats.count { map.issue(it) != null } }
            if (issueCount > 0) Text("⚠ $issueCount 个标记存在重复、异常或空编号，已禁选，避免选错。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
            Text("候选优先顺序（不是同时预约多个座位）")
            if (selected.isEmpty()) Text("尚未选择，请在图上点击座位")
            selected.forEachIndexed { index, id ->
                Column {
                    Text("${index + 1}. $id")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = index > 0, onClick = {
                            val moved = selected.toMutableList()
                            moved[index] = moved[index - 1]; moved[index - 1] = id
                            onChange(moved)
                        }) { Text("前移") }
                        OutlinedButton(onClick = { onChange(selected - id) }) { Text("移除") }
                    }
                }
            }
            if (selected.isNotEmpty()) OutlinedButton(onClick = { onChange(emptyList()) }) { Text("清空候选座位") }
            map.selectionError(selected)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("选好日期和时段后，在下方点“立即演练”或“立即预约所选座位”；定时预约请保存并安排任务。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

