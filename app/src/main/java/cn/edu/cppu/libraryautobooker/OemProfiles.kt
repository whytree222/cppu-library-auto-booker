package cn.edu.cppu.libraryautobooker

import android.os.Build
import java.util.Locale

enum class OemFamily { XIAOMI, HUAWEI, HONOR, OPPO, VIVO, SAMSUNG, MEIZU, GENERIC }

/**
 * Brand-specific background guidance. These switches are private to each system, so the app
 * explains the path instead of pretending it can read or change the setting.
 */
data class OemProfile(
    val family: OemFamily,
    val brand: String,
    val system: String?,
    val steps: List<String>,
    val summary: String
) {
    val displayName: String
        get() = if (system.isNullOrBlank()) brand else "$brand · $system"
}

object OemProfiles {
    val xiaomi = OemProfile(
        OemFamily.XIAOMI,
        "小米 / Redmi",
        "HyperOS / MIUI",
        listOf(
            "设置 → 应用设置 → 应用管理 → 本应用",
            "开启“自启动”，将省电策略设为“无限制”",
            "在最近任务中锁定本应用，减少后台清理"
        ),
        "开启自启动，并将省电策略设为“无限制”"
    )

    val huawei = OemProfile(
        OemFamily.HUAWEI,
        "华为",
        "EMUI / HarmonyOS",
        listOf(
            "设置 → 应用和服务 → 应用启动管理 → 本应用",
            "关闭“自动管理”，允许自启动、关联启动和后台活动",
            "设置 → 电池，关闭会限制后台的省电模式"
        ),
        "关闭应用启动管理的“自动管理”，允许后台活动"
    )

    val honor = OemProfile(
        OemFamily.HONOR,
        "荣耀",
        "MagicOS",
        listOf(
            "设置 → 应用 → 应用启动管理 → 本应用",
            "关闭“自动管理”，允许自启动和后台活动",
            "设置 → 电池，将本应用设为不受限制"
        ),
        "关闭应用启动管理的“自动管理”，允许后台活动"
    )

    val oppo = OemProfile(
        OemFamily.OPPO,
        "OPPO / 一加 / realme",
        "ColorOS / OxygenOS / realme UI",
        listOf(
            "设置 → 电池 → 应用耗电管理 → 本应用",
            "允许完全后台行为（或“后台运行”）",
            "设置 → 应用 → 自启动，允许本应用自启动"
        ),
        "允许完全后台行为，并在应用管理中开启自启动"
    )

    val vivo = OemProfile(
        OemFamily.VIVO,
        "vivo / iQOO",
        "OriginOS",
        listOf(
            "设置 → 电池 → 后台耗电管理 → 本应用",
            "允许后台高耗电",
            "i管家 → 应用管理 → 自启动，允许本应用"
        ),
        "允许后台高耗电，并在 i 管家中开启自启动"
    )

    val samsung = OemProfile(
        OemFamily.SAMSUNG,
        "三星",
        "One UI",
        listOf(
            "设置 → 电池 → 后台使用限制",
            "将本应用加入“从未休眠的应用”",
            "设备维护 → 电池 → 应用电源管理，允许后台活动"
        ),
        "将本应用加入“从未休眠的应用”，允许后台活动"
    )

    val meizu = OemProfile(
        OemFamily.MEIZU,
        "魅族",
        "Flyme",
        listOf(
            "手机管家 → 权限管理 → 后台管理",
            "允许本应用后台运行和自启动",
            "设置 → 应用管理 → 本应用 → 电池，取消后台限制"
        ),
        "允许后台运行和自启动"
    )

    val generic = OemProfile(
        OemFamily.GENERIC,
        "Android 设备",
        null,
        listOf(
            "系统设置 → 应用 → 本应用",
            "允许后台运行，并在电池设置中取消限制",
            "不同品牌页面名称不同，以系统实际页面为准"
        ),
        "允许后台运行，并在电池设置中取消限制"
    )

    val all: List<OemProfile> = listOf(xiaomi, huawei, honor, oppo, vivo, samsung, meizu, generic)

    fun detect(manufacturer: String?, brand: String?): OemProfile {
        val keys = listOf(brand, manufacturer)
            .mapNotNull { it?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }
        fun matches(vararg names: String) = keys.any { key -> names.any { key.contains(it) } }
        return when {
            // Some older Honor phones still report HUAWEI as the manufacturer.
            matches("honor") -> honor
            matches("xiaomi", "redmi", "poco") -> xiaomi
            matches("huawei") -> huawei
            matches("oppo", "oneplus", "realme") -> oppo
            matches("vivo", "iqoo") -> vivo
            matches("samsung") -> samsung
            matches("meizu") -> meizu
            else -> generic
        }
    }

    fun current(): OemProfile = detect(Build.MANUFACTURER, Build.BRAND)
}
