package et.alpha.mezgeb.capture.vendor

/**
 * Deep links into the vendor screens that decide whether a background listener is
 * allowed to keep living.
 *
 * Stock Android's battery optimisation settings are not the whole story on the
 * devices this app is built for. MIUI, ColorOS, FuntouchOS, EMUI and Transsion's
 * HiOS/XOS each ship an additional "autostart" or "protected app" list, off by
 * default, and an app that is not on it gets its notification listener killed
 * silently within hours. Sending the user to the stock screen on those devices is
 * advice that does not work.
 *
 * These component names are undocumented and vary by firmware version, so every
 * caller must check the target resolves before using it and fall back to the stock
 * screen — see [et.alpha.mezgeb.capture.vendor.BatteryOptimizationHelper].
 */
enum class DeviceVendor {
    XIAOMI,
    OPPO,
    VIVO,
    HUAWEI,
    TRANSSION,
    SAMSUNG,
    OTHER,
}

/** A vendor activity to try, most specific first. */
data class VendorSettingsTarget(val packageName: String, val className: String)

object VendorBatterySettings {

    /** [android.os.Build.MANUFACTURER] is free text, so match loosely. */
    fun vendorFor(manufacturer: String?): DeviceVendor {
        val name = manufacturer?.trim()?.lowercase().orEmpty()
        return when {
            name.isEmpty() -> DeviceVendor.OTHER
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                DeviceVendor.XIAOMI
            name.contains("oppo") || name.contains("realme") || name.contains("oneplus") ->
                DeviceVendor.OPPO
            name.contains("vivo") || name.contains("iqoo") -> DeviceVendor.VIVO
            name.contains("huawei") || name.contains("honor") -> DeviceVendor.HUAWEI
            // Transsion sells Tecno, Infinix and itel, which between them are a large
            // share of the phones in this market. One power manager across all three.
            name.contains("tecno") || name.contains("infinix") ||
                name.contains("itel") || name.contains("transsion") -> DeviceVendor.TRANSSION
            name.contains("samsung") -> DeviceVendor.SAMSUNG
            else -> DeviceVendor.OTHER
        }
    }

    /** Ordered candidates. Empty for [DeviceVendor.OTHER] — the stock screen is right there. */
    fun targetsFor(vendor: DeviceVendor): List<VendorSettingsTarget> = when (vendor) {
        DeviceVendor.XIAOMI -> listOf(
            VendorSettingsTarget(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )

        DeviceVendor.OPPO -> listOf(
            VendorSettingsTarget(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            VendorSettingsTarget(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            VendorSettingsTarget(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        )

        DeviceVendor.VIVO -> listOf(
            VendorSettingsTarget(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            VendorSettingsTarget(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        )

        DeviceVendor.HUAWEI -> listOf(
            VendorSettingsTarget(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            VendorSettingsTarget(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )

        DeviceVendor.TRANSSION -> listOf(
            VendorSettingsTarget(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity",
            ),
        )

        DeviceVendor.SAMSUNG -> listOf(
            VendorSettingsTarget(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            VendorSettingsTarget(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )

        DeviceVendor.OTHER -> emptyList()
    }
}
