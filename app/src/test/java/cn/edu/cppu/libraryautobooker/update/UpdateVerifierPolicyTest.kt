package cn.edu.cppu.libraryautobooker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateVerifierPolicyTest {
    private fun mismatch(
        archivePackage: String? = "cn.edu.cppu.libraryautobooker",
        archiveVersionCode: Long = 51,
        archiveVersionName: String? = "0.19.1",
        archiveSigners: List<String> = listOf("abc"),
        archiveMultiple: Boolean = false,
        installedSigners: List<String> = listOf("abc"),
        installedMultiple: Boolean = false
    ) = UpdateVerifierPolicy.mismatch(
        expectedPackage = "cn.edu.cppu.libraryautobooker",
        expectedVersionCode = 51,
        expectedVersionName = "0.19.1",
        archivePackage = archivePackage,
        archiveVersionCode = archiveVersionCode,
        archiveVersionName = archiveVersionName,
        archiveSigners = archiveSigners,
        archiveMultipleSigners = archiveMultiple,
        installedSigners = installedSigners,
        installedMultipleSigners = installedMultiple
    )

    @Test fun acceptsAMatchingPackageAndSigner() {
        assertNull(mismatch())
    }

    @Test fun rejectsPackageVersionOrNameMismatches() {
        assertEquals("安装包包名与当前应用不一致", mismatch(archivePackage = "other.package"))
        assertEquals("安装包版本号与更新信息不一致", mismatch(archiveVersionCode = 50))
        assertEquals("安装包版本名称与更新信息不一致", mismatch(archiveVersionName = "0.19.0"))
    }

    @Test fun rejectsMultipleOrMissingSigners() {
        assertEquals("安装包包含多个签名，已拒绝", mismatch(archiveMultiple = true))
        assertEquals("无法读取安装包签名", mismatch(archiveSigners = emptyList()))
        assertEquals("当前应用包含多个签名，无法安全更新", mismatch(installedMultiple = true))
        assertEquals("无法读取当前应用签名", mismatch(installedSigners = emptyList()))
    }

    @Test fun rejectsADifferentSigner() {
        assertEquals(
            "安装包签名与当前应用不一致，已拒绝",
            mismatch(installedSigners = listOf("def"))
        )
    }
}
