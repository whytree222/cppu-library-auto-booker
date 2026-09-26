package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import org.json.JSONArray
import org.json.JSONObject

object AutomationScript {
    fun build(template: String, config: BookingConfig): String {
        val json = JSONObject().apply {
            put("dryRun", config.dryRun)
            put("reserveTomorrow", config.reserveTomorrow)
            put("roomKeywords", config.roomKeywords.split(',').map(String::trim).filter(String::isNotEmpty))
            put("seatKeywords", config.seatKeywords.split(',').map(String::trim).filter(String::isNotEmpty))
            put("seatNumbers", JSONArray(config.seatNumbers))
            put("seatChoices", JSONArray().apply {
                config.seatChoices.forEach { seat ->
                    put(JSONObject().apply {
                        put("label", seat.label)
                        put("selector", seat.selector)
                        put("x", seat.xFraction)
                        put("y", seat.yFraction)
                    })
                }
            })
            put("startTime", config.startTime)
            put("endTime", config.endTime)
        }.toString()
        return template.replace("__BOOKING_CONFIG__", json)
    }
}
