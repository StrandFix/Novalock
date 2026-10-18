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

    fun isSafeApp(packageName: String?): Boolean =
        !packageName.isNullOrBlank() &&
        !packageName.startsWith("pl.iskri.pocketlock") &&
        packageName != "com.android.systemui" &&
        packageName != "android" &&
        !packageName.contains("inputmethod", ignoreCase = true)
}
