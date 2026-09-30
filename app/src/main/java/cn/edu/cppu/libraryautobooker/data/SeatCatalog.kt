package cn.edu.cppu.libraryautobooker.data

/** The 94 identifiers in the supplied room 26 seat map. */
object SeatCatalog {
    val numbers: List<String> = (1..23).flatMap { table ->
        ('A'..'D').map { letter -> "G${table.toString().padStart(3, '0')}$letter" }
    } + listOf("YXS1", "YXS2")

    fun error(numbers: List<String>): String? {
        val invalid = numbers.filterNot { Regex("^(G\\d{3}[A-Z]|YXS\\d+)$").matches(it) }
        if (invalid.isNotEmpty()) return "座位号格式错误：${invalid.joinToString("、")}；例如 G023D、YXS1，每行一个"
        val unknown = numbers.filterNot { it in this.numbers }
        if (unknown.isNotEmpty()) return "过刊阅览室没有这些座位号：${unknown.joinToString("、")}；范围为 G001A–G023D（每桌 A/B/C/D）及 YXS1、YXS2"
        return null
    }
}

