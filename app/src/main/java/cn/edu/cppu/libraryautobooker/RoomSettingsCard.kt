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

/** A newly chosen room must never inherit another room's reservation target. */
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
            Text("内置一至四楼 7 间阅览室的座位图和独立时间表。切换房间立即读取本地数据；切换今天／明天不联网、不弹出同步页面。",
                style = MaterialTheme.typography.bodySmall)
            Text("切换房间需重新选座、选时段。同一房间按完整起止时间保留已选项；已选时段有变化时需重新选择，规则变化会取消旧定时任务。",
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
                else "预约方式：选择该房间的固定时段（共 ${selected.slots.size} 个）",
                style = MaterialTheme.typography.bodySmall)
            Text(if (selected.slotsDate.isEmpty()) "时段来源：缓存，尚未按使用日期核对；学校未开放或同步失败时保留缓存，预约时仍精确核对学校网页。"
                else "本地时间表最近核对的使用日期：${selected.slotsDate}；不代表其他日期开放或座位空闲。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
