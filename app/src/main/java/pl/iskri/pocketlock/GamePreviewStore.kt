package pl.iskri.pocketlock

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Only holds one recent frame in process memory; screenshots never leave
 * the device, nor are they persisted to storage.
 */
object GamePreviewStore {
    data class Preview(val packageName: String?, val bitmap: Bitmap?)

    @Volatile var isLocked: Boolean = false
        private set
    @Volatile var currentPackage: String? = null
        private set
    @Volatile var captureStatus: String = "Waiting for the first game frame"
        private set

    private var lastBitmap: Bitmap? = null
    private var capturedPackage: String? = null
    private var frameTakenAt: Long = 0L
    private var frozen = Preview(null, null)

    private fun candidate(name: String?): Boolean =
        !name.isNullOrBlank() &&
        !name.startsWith("pl.iskri.pocketlock") &&
        name != "com.android.systemui" &&
        name != "android" &&
        !name.contains("inputmethod", ignoreCase = true)

    @Synchronized fun observeForeground(name: String) {
        if (!isLocked && candidate(name)) {
            currentPackage = name
        }
    }

    @Synchronized fun rememberFrame(name: String, bitmap: Bitmap) {
        if (isLocked || currentPackage != name) return
        lastBitmap = bitmap
        capturedPackage = name
        frameTakenAt = SystemClock.elapsedRealtime()
        captureStatus = "Screenshot ready (" + name + ")"
    }

    fun noteCaptureFailure(errorCode: Int) {
        captureStatus = "Screenshot unavailable (Android code " + errorCode + ")"
    }

    @Synchronized fun freeze(name: String?) {
        if (isLocked) return
        val candidateName = if (candidate(name)) name else currentPackage
        // A screen-off event can report System UI rather than the running game.
        // The screenshot service's last valid foreground app is safer then.
        val selected = candidateName ?: capturedPackage
        val frameFresh = SystemClock.elapsedRealtime() - frameTakenAt < 45000L
        val picture = if (frameFresh && selected == capturedPackage) lastBitmap else null
        frozen = Preview(selected, picture)
        isLocked = true
    }

    @Synchronized fun preview(): Preview = frozen

    @Synchronized fun unfreeze() {
        isLocked = false
        frozen = Preview(null, null)
    }
}
