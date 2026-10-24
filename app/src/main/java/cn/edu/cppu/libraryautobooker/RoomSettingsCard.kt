package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.RoomProfile

/** A newly chosen room must never inherit another room's reservation target. */
internal fun resetRoomDraft(config: BookingConfig, room: RoomProfile): BookingConfig =
    config.copy(room = room, seatNumbers = emptyList(), seatChoices = emptyList(), mapSeats = emptyList(),
        selectedSlots = emptyList(), appendLastThree = false).withPending(emptyList())

/** Cached profiles are selected explicitly; browsing a floor alone never changes a task. */
@OptIn(ExperimentalLayoutApi::class)
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
    val floorName = groups.firstOrNull { it.first == floor }?.second.orEmpty().ifBlank { "未分组" }

    AppCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "阅览室",
            subtitle = "${selected.groupName.ifBlank { "未分组" }} · ${selected.name}",
            action = {
                TextButton(onClick = onSync, modifier = Modifier.testTag("sync-rooms")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("从学校同步", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SelectorField(
                    label = "楼层",
                    value = floorName,
                    modifier = Modifier.testTag("floor-selector"),
                    onClick = { floorsExpanded = true }
                )
                DropdownMenu(expanded = floorsExpanded, onDismissRequest = { floorsExpanded = false }) {
                    groups.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name.ifBlank { "未分组" }) }, onClick = {
                            floor = id
                            floorsExpanded = false
                        })
                    }
                }
            }
            Box(Modifier.weight(1.6f)) {
                SelectorField(
                    label = "阅览室",
                    value = if (selected.groupId == floor) selected.name else "选择这一层",
                    enabled = visibleRooms.isNotEmpty(),
                    modifier = Modifier.testTag("room-selector"),
                    onClick = { roomsExpanded = true }
                )
                DropdownMenu(expanded = roomsExpanded, onDismissRequest = { roomsExpanded = false }) {
                    visibleRooms.forEach { room ->
                        DropdownMenuItem(
                            text = { Text("${if (room.id == selected.id) "✓ " else ""}${room.name}") },
                            onClick = {
                                roomsExpanded = false
                                onSelect(room)
                            })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (selected.timeMode == "free") "自选起止时间"
                else "固定时段 · ${selected.slots.size} 个", StatusTone.Neutral)
            StatusPill(
                if (selected.slotsDate.isEmpty()) "时间表待核对" else "时段表 · ${selected.slotsDate}",
                if (selected.slotsDate.isEmpty()) StatusTone.Warning else StatusTone.Neutral
            )
        }
        ExpandableText(
            if (selected.slotsDate.isEmpty())
                "本地时间表尚未按使用日期核对；学校未开放或同步失败时保留缓存，真正预约时仍会精确核对学校网页。切换房间需重新选座和选时段，旧定时任务会被取消。"
            else
                "时段表核对日期：${selected.slotsDate}，不代表其他日期开放或座位空闲。切换房间需重新选座和选时段，规则变化会取消旧定时任务。",
            collapsedMaxLines = 2
        )
    }
}

@Composable
private fun SelectorField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
