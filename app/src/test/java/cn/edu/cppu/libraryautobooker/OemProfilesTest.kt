package cn.edu.cppu.libraryautobooker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemProfilesTest {
    @Test fun detectsMainstreamBrandsFromEitherBuildField() {
        assertEquals(OemFamily.XIAOMI, OemProfiles.detect("Xiaomi", "Redmi").family)
        assertEquals(OemFamily.XIAOMI, OemProfiles.detect("POCO", null).family)
        assertEquals(OemFamily.HUAWEI, OemProfiles.detect("HUAWEI", "HUAWEI").family)
        assertEquals(OemFamily.HONOR, OemProfiles.detect("HUAWEI", "HONOR").family)
        assertEquals(OemFamily.OPPO, OemProfiles.detect("OnePlus", "OnePlus").family)
        assertEquals(OemFamily.OPPO, OemProfiles.detect(null, "realme").family)
        assertEquals(OemFamily.VIVO, OemProfiles.detect("vivo", "iQOO").family)
        assertEquals(OemFamily.SAMSUNG, OemProfiles.detect("samsung", null).family)
        assertEquals(OemFamily.MEIZU, OemProfiles.detect("Meizu", "meizu").family)
    }

    @Test fun unknownOrMissingBrandUsesGenericSteps() {
        assertEquals(OemFamily.GENERIC, OemProfiles.detect("Sony", "Xperia").family)
        assertEquals(OemFamily.GENERIC, OemProfiles.detect(null, null).family)
        assertEquals(OemFamily.GENERIC, OemProfiles.detect("", " ").family)
    }

    @Test fun everyProfileHasCompleteGuidance() {
        OemProfiles.all.forEach { profile ->
            assertTrue(profile.brand.isNotBlank())
            assertTrue(profile.summary.isNotBlank())
            assertTrue(profile.steps.isNotEmpty())
            profile.steps.forEach { step -> assertTrue(step.isNotBlank()) }
        }
        assertTrue(OemProfiles.xiaomi.displayName.contains("HyperOS"))
        assertEquals("Android 设备", OemProfiles.generic.displayName)
    }
}
