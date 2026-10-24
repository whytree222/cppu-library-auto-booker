package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatMapCard(map: RoomSeatMap?, selected: List<String>, onChange: (List<String>) -> Unit) {
    AppCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = "座位图",
            subtitle = map?.let { "${it.room.groupName} · ${it.room.name} · ${it.seats.size} 个座位标记" }
                ?: "当前阅览室暂无座位图"
        )
        if (map == null) {
            Text("此阅览室尚无已核对的座位图，不能猜测座位编号。请重新同步或等待补充布局。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            return@AppCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SeatLegend(MaterialTheme.colorScheme.surface, "可选")
            SeatLegend(MaterialTheme.colorScheme.primary, "已选")
            SeatLegend(MaterialTheme.colorScheme.errorContainer, "异常 / 禁选")
        }
        var message by remember(map.room.id) { mutableStateOf("") }
        key(map.room.id) {
            val byPosition = remember(map) { map.seats.associateBy { it.row to it.column } }
            Surface(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            ) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                    Column(Modifier.padding(8.dp)) {
                        repeat(map.rows) { row ->
                            Row {
                                repeat(map.columns) { column ->
                                    val seat = byPosition[row to column]
                                    Box(Modifier.size(width = 64.dp, height = 48.dp).padding(2.dp)) {
                                        if (seat != null) {
                                            val issue = map.issue(seat)
                                            val isSelected = seat.id in selected
                                            val description = "${seat.id.ifBlank { "无编号" }}，第 ${row + 1} 行第 ${column + 1} 列" +
                                                if (issue != null) "，$issue，不可选择"
                                                else if (isSelected) "，已选，再点取消" else "，点击加入候选"
                                            val click = {
                                                if (selected.size >= 10 && !isSelected) {
                                                    message = "最多选择 10 个候选座位，请先移除一个"
                                                } else {
                                                    message = ""
                                                    onChange(toggleMapSeat(map, row, column, selected))
                                                }
                                            }
                                            Surface(
                                                onClick = click,
                                                enabled = issue == null,
                                                modifier = Modifier.size(width = 60.dp, height = 44.dp)
                                                    .testTag("seat-$row-$column")
                                                    .semantics { contentDescription = description },
                                                shape = RoundedCornerShape(8.dp),
                                                color = when {
                                                    issue != null -> MaterialTheme.colorScheme.surfaceContainerHighest
                                                    isSelected -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.surface
                                                },
                                                contentColor = when {
                                                    issue != null -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                                border = if (issue == null && !isSelected)
                                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        if (issue != null) "⚠\n${seat.id.ifBlank { "无编号" }}" else seat.id,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        textAlign = TextAlign.Center,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 2.dp)
                                                    )
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
        }
        val issueCount = remember(map) { map.seats.count { map.issue(it) != null } }
        if (issueCount > 0) {
            Text("⚠ $issueCount 个标记存在重复、异常或空编号，已禁选，避免选错。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        ExpandableText(
            "点击座位加入候选顺序，再点一次取消；最多 10 个。布局采集于 ${map.capturedDate}，不代表实时余座；运行时会重新核对学校状态。",
            collapsedMaxLines = 2
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("候选顺序", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${selected.size} / 10", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("按优先级排序，运行时只会尝试其中一个座位。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Text("尚未选择座位，请在座位图中点击添加。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            ) {
                Column {
                    selected.forEachIndexed { index, id ->
                        val move: (Int) -> Unit = { target ->
                            val ordered = selected.toMutableList()
                            val moved = ordered.removeAt(index)
                            ordered.add(target, moved)
                            onChange(ordered)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text(id, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { move(index - 1) }, enabled = index > 0,
                                modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "前移 $id",
                                    modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { move(index + 1) }, enabled = index < selected.lastIndex,
                                modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "后移 $id",
                                    modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onChange(selected - id) },
                                modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "移除 $id",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        if (index != selected.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 44.dp))
                        }
                    }
                }
            }
            TextButton(onClick = { onChange(emptyList()) }, modifier = Modifier.align(Alignment.Start)) {
                Text("清空候选座位")
            }
        }
        map.selectionError(selected)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SeatLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(4.dp), color = color,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.size(14.dp)) {}
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
