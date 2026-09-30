package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.SeatCatalog
import org.junit.Assert.*
import org.junit.Test

class SeatCatalogTest {
    @Test fun allRoomIdentifiersAreAccepted() {
        assertEquals(94, SeatCatalog.numbers.toSet().size)
        assertNull(SeatCatalog.error(SeatCatalog.numbers))
        assertTrue(SeatCatalog.numbers.containsAll(listOf("G001A", "G023D", "YXS1", "YXS2")))
    }
    @Test fun formatAndUnknownNumberHaveDifferentReasons() {
        assertTrue(SeatCatalog.error(listOf("G23D"))!!.contains("格式错误"))
        assertTrue(SeatCatalog.error(listOf("G024A"))!!.contains("没有这些座位号"))
        assertTrue(SeatCatalog.error(listOf("YXS3"))!!.contains("没有这些座位号"))
    }
}

