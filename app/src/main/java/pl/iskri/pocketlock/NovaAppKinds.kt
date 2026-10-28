package pl.iskri.pocketlock

import android.content.Context
import android.content.pm.PackageManager

/** Detect the Cocoon frontend without hard-coding only one package name. */
object NovaAppKinds {
    fun isCocoon(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName == "rip.moth.cocoonshell" ||
            packageName.contains("cocoon", ignoreCase = true)) return true
        return try {
            val manager = context.packageManager
            val label = manager.getApplicationLabel(
                manager.getApplicationInfo(packageName, 0)).toString()
            label.contains("cocoon", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /**
     * Android Settings and permission settings screens are treated as a
     * dedicated system view. No screenshot is needed or retained.
     */
    fun isSettings(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName == "com.android.settings" ||
            packageName == "com.google.android.settings" ||
            packageName == "com.android.tv.settings" ||
            packageName == "com.google.android.tv.settings" ||
            packageName == "com.android.car.settings" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName == "com.android.packageinstaller" ||
            packageName == "com.google.android.packageinstaller") return true
        // Other manufacturer Settings variants with their own package names.
        return try {
            val manager = context.packageManager
            val label = manager.getApplicationLabel(
                manager.getApplicationInfo(packageName, 0)).toString()
            label.equals("Settings", ignoreCase = true) ||
                label.equals("Inställningar", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun isRetroArch(packageName: String?): Boolean =
        packageName?.startsWith("com.retroarch") == true

    /**
     * Bound Cocoon-selected game art only to a plausible emulator launch,
     * not a browser, phone Settings, file manager or app store.
     * The emulator's own window-title detection remains independent.
     */
    fun isLikelyEmulator(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank() || isCocoon(context, packageName) ||
            isSettings(context, packageName) || !isSafeApp(packageName)) return false
        val token = packageName.lowercase(java.util.Locale.ROOT)
        if (listOf(
                "dolphin", "retroarch", "yuzu", "eden", "sudachi", "suyu",
                "azahar", "citra", "duckstation", "aethersx2", "nethersx2",
                "ppsspp", "melonds", "drastic", "pizzaboy", "pizza.boy",
                "redream", "flycast", "mupen", "n64", "snes9x", "epsxe",
                "lime3ds", "lemuroid", "vita3k", "scummvm", "dosbox",
                "cynth", "pcsx", "skyline", "ryujinx", "retrobox"
            ).any { it in token }) return true
        return try {
            val pm = context.packageManager
            val label = pm.getApplicationLabel(
                pm.getApplicationInfo(packageName, 0)
            ).toString().lowercase(java.util.Locale.ROOT)
            label.contains("emulator") || label.contains("emulation") ||
                label.contains("retroarch")
        } catch (_: Exception) { false }
    }

    fun isSafeApp(packageName: String?): Boolean =
        !packageName.isNullOrBlank() &&
        !packageName.startsWith("pl.iskri.pocketlock") &&
        packageName != "com.android.systemui" &&
        packageName != "android" &&
        !packageName.contains("inputmethod", ignoreCase = true)
}
