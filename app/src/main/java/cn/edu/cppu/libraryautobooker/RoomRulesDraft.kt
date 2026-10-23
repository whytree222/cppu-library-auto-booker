package cn.edu.cppu.libraryautobooker

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import cn.edu.cppu.libraryautobooker.data.RoomSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A different verification date alone does not change the requested reservation. */
internal fun roomRulesChanged(previous: RoomProfile, updated: RoomProfile): Boolean =
    previous.copy(slotsDate = "") != updated.copy(slotsDate = "")

private fun sameRoomIdentity(previous: RoomProfile, updated: RoomProfile): Boolean =
    previous.id == updated.id && previous.name == updated.name &&
        previous.groupId == updated.groupId && previous.groupName == updated.groupName &&
        previous.timeMode == updated.timeMode

/** A foreground refresh must not invalidate a plan for another room or usage date. */
internal fun roomRulesAffectPlan(
    plan: BookingConfig,
    updated: RoomProfile,
    zone: ZoneId = ZoneId.systemDefault()
): Boolean {
    if (!plan.enabled || plan.room.validationError() != null || updated.validationError() != null ||
        !RoomProfile.validSlotsDate(updated.slotsDate) || !sameRoomIdentity(plan.room, updated) ||
        !roomRulesChanged(plan.room, updated)) return false
    val scheduled = plan.pendingTimes().firstOrNull() ?: return false
    val useDate = runCatching {
        Instant.ofEpochMilli(scheduled).atZone(zone).toLocalDate()
            .plusDays(if (plan.reserveTomorrow) 1L else 0L).toString()
    }.getOrNull()
    return useDate == updated.slotsDate
}

/**
 * Adopt only this room's verified rules. Selected ranges are matched to the second,
 * never by their ordinal or approximate time. The caller cancels any actual alarm
 * before adopting changed rules; this pure function only clears the saved queue.
 */
internal fun applyRoomRules(config: BookingConfig, updated: RoomProfile): BookingConfig {
    require(sameRoomIdentity(config.room, updated)) { "同步结果不属于当前阅览室" }
    require(config.room.validationError() == null && updated.validationError() == null) {
        "阅览室时间规则无效"
    }

    val selected = exactUpdatedSelection(config, updated)
    val adopted = config.copy(
        room = updated,
        selectedSlots = selected.orEmpty(),
        appendLastThree = selected != null && config.appendLastThree &&
            updated.appendCompatible && selected == listOf(0, 1, 2, 3)
    )
    return if (roomRulesChanged(config.room, updated)) adopted.withPending(emptyList()) else adopted
}

private fun validSelection(indexes: List<Int>, count: Int): Boolean =
    indexes.isNotEmpty() && indexes.size <= 4 && indexes == indexes.distinct().sorted() &&
        indexes.all { it in 0 until count } && indexes.last() - indexes.first() + 1 == indexes.size

private fun exactUpdatedSelection(config: BookingConfig, updated: RoomProfile): List<Int>? {
    if (config.room.timeMode != "fixed" || !validSelection(config.selectedSlots, config.room.slots.size))
        return null
    val selectedRanges: List<RoomSlot> = config.selectedSlots.map(config.room.slots::get)
    val updatedIndexes = selectedRanges.map { selected ->
        updated.slots.withIndex().singleOrNull { it.value == selected }?.index ?: return null
    }
    return updatedIndexes.takeIf { validSelection(it, updated.slots.size) }
}

/** Binds an asynchronous response to the exact room, usage day and requested date. */
internal data class RoomRulesTicket(
    val roomId: String,
    val name: String,
    val groupId: String,
    val groupName: String,
    val timeMode: String,
    val reserveTomorrow: Boolean,
    val expectedDate: String
) {
    init {
        require(RoomProfile.validSlotsDate(expectedDate)) { "同步目标日期无效" }
    }

    fun accepts(updated: RoomProfile, useDay: Boolean, date: String): Boolean =
        updated.validationError() == null && updated.id == roomId && updated.name == name &&
            updated.groupId == groupId && updated.groupName == groupName && updated.timeMode == timeMode &&
            useDay == reserveTomorrow && date == expectedDate && updated.slotsDate == expectedDate

    fun accepts(updated: RoomProfile, useDay: Int, date: String): Boolean =
        useDay in 0..1 && accepts(updated, useDay == 1, date)

    companion object {
        fun create(config: BookingConfig, expectedDate: LocalDate): RoomRulesTicket {
            require(config.room.validationError() == null) { "同步阅览室无效" }
            return config.room.let { room ->
                RoomRulesTicket(room.id, room.name, room.groupId, room.groupName, room.timeMode,
                    config.reserveTomorrow, expectedDate.toString())
            }
        }
    }
}
