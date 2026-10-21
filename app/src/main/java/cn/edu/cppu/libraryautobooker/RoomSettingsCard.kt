package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.RoomProfile

/** A new identity or time-rule snapshot must never inherit the old room's reservation target. */
internal fun resetRoomDraft(config: BookingConfig, room: RoomProfile): BookingConfig =
    config.copy(room = room, seatNumbers = emptyList(), seatChoices = emptyList(), mapSeats = emptyList(),
        selectedSlots = emptyList(), appendLastThree = false).withPending(emptyList())

/** Cached profiles are selected explicitly; browsing a floor alone never changes a task. */
@Composable
fun RoomSettingsCard(
    selected: RoomProfile,
    rooms: List<RoomProfile>,
    onSelect: (RoomProfile) -> Unit,
    onSync: () -> Unit
) {
    val available = remember(rooms, selected) { (rooms + selected).distinctBy { it.id }
        .sortedWith(compareBy({ it.groupId.toIntOrNull() ?: Int.MAX_VALUE }, { it.id.toIntOrNull() ?: Int.MAX_VALUE })) }
    val groups = remember(available) { available.map { it.groupId to it.groupName }.distinctBy { it.first } }
    var floor by remember { mutableStateOf(selected.groupId) }
    var floorsExpanded by remember { mutableStateOf(false) }
    var roomsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(selected.groupId) { floor = selected.groupId }
    val visibleRooms = available.filter { it.groupId == floor }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择楼层和阅览室", style = MaterialTheme.typography.titleMedium)
            Text("当前预约：${selected.groupName.ifBlank { "未分组" }} · ${selected.name}")
            Text("内置已核对的一至四楼 7 间阅览室，也可同步学校最新时间规则。切换房间或规则变化会取消旧定时任务并清空座位、时段，需重新确认保存。",
                style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { floorsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    val name = groups.firstOrNull { it.first == floor }?.second.orEmpty().ifBlank { "未分组" }
                    Text("浏览楼层：$name ▾")
                }
                DropdownMenu(expanded = floorsExpanded, onDismissRequest = { floorsExpanded = false }) {
                    groups.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name.ifBlank { "未分组" }) }, onClick = {
                            floor = id
                            floorsExpanded = false
                        })
                    }
                }
            }
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { roomsExpanded = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = visibleRooms.isNotEmpty()) {
                    Text(if (selected.groupId == floor) "选择阅览室：${selected.name} ▾" else "选择这一层的阅览室 ▾")
                }
                DropdownMenu(expanded = roomsExpanded, onDismissRequest = { roomsExpanded = false }) {
                    visibleRooms.forEach { room ->
                        DropdownMenuItem(text = { Text("${if (room.id == selected.id) "✓ " else ""}${room.name}") },
                            onClick = {
                                roomsExpanded = false
                                onSelect(room)
                            })
                    }
                }
            }
            OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("从学校同步阅览室") }
            Text(if (selected.timeMode == "free") "预约方式：填写开始和结束时间"
                else "预约方式：选择该房间的固定时段（已同步 ${selected.slots.size} 个）",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

