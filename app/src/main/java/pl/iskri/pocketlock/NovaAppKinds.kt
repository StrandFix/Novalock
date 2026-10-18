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

    fun isRetroArch(packageName: String?): Boolean =
        packageName?.startsWith("com.retroarch") == true

    fun isSafeApp(packageName: String?): Boolean =
        !packageName.isNullOrBlank() &&
        !packageName.startsWith("pl.iskri.pocketlock") &&
        packageName != "com.android.systemui" &&
        packageName != "android" &&
        !packageName.contains("inputmethod", ignoreCase = true)
}
