package et.alpha.mezgeb.capture.vendor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a detected vendor into an [Intent] that actually opens on this firmware.
 *
 * The stock screen is [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] — a
 * list the user picks Mezgeb out of. Deliberately not
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which pops a one-tap dialog but
 * requires a permission Play restricts to a short list of app categories that does
 * not include this one. The extra tap is worth not being removed from the store.
 */
@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    val vendor: DeviceVendor get() = VendorBatterySettings.vendorFor(Build.MANUFACTURER)

    /** True when this device has a vendor screen worth sending the user to. */
    fun hasVendorScreen(): Boolean = vendorIntent() != null

    /**
     * The vendor's autostart screen when one resolves, otherwise the stock battery
     * optimisation list. Never null: there is always somewhere useful to send them.
     */
    fun settingsIntent(): Intent = vendorIntent() ?: stockIntent()

    private fun vendorIntent(): Intent? = VendorBatterySettings.targetsFor(vendor)
        .asSequence()
        .map { target ->
            Intent().setComponent(ComponentName(target.packageName, target.className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        .firstOrNull { intent ->
            // Undocumented components get renamed between firmware versions. Resolving
            // first is what stops a "keep Mezgeb running" button from throwing
            // ActivityNotFoundException on the one device that needed it most.
            intent.resolveActivity(context.packageManager) != null
        }

    private fun stockIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
