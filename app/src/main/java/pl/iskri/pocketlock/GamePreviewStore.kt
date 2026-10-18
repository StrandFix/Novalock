package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Only the most recent frame is kept in RAM. Nothing is stored or transmitted.
 * The frozen preview persists while Android switches lock-screen windows.
 */
object GamePreviewStore {
    data class Preview(
        val packageName: String?,
        val bitmap: Bitmap?,
        val isHome: Boolean = false,
        val isSettings: Boolean = false
    )

    @Volatile var isLocked: Boolean = false
        private set
    @Volatile var currentPackage: String? = null
        private set
    @Volatile var captureStatus: String = "Waiting for a game image"
        private set

    private var lastBitmap: Bitmap? = null
    private var capturedPackage: String? = null
    private var frameTakenAt: Long = 0L
    private var frozen = Preview(null, null)

    @Synchronized fun observeForeground(context: Context, name: String) {
        if (isLocked || !NovaAppKinds.isSafeApp(name)) return
        if (currentPackage != name) {
            currentPackage = name
            if (NovaAppKinds.isCocoon(context, name) ||
                NovaAppKinds.isSettings(context, name)) {
                lastBitmap = null
                capturedPackage = null
                captureStatus = if (NovaAppKinds.isSettings(context, name))
                    "Settings - screenshots disabled"
                else "Cocoon Home Menu - screenshots disabled"
            }
        }
    }

    @Synchronized fun rememberFrame(name: String, bitmap: Bitmap) {
        if (isLocked || currentPackage != name) return
        lastBitmap = bitmap
        capturedPackage = name
        frameTakenAt = SystemClock.elapsedRealtime()
        captureStatus = "Screenshot ready: $name"
    }

    fun noteCaptureFailure(errorCode: Int) {
        captureStatus = "Screenshot blocked or unavailable (Android: $errorCode)"
    }

    @Synchronized fun freeze(context: Context, reportedPackage: String?) {
        if (isLocked) return
        val observed = currentPackage
        val reported = reportedPackage?.takeIf(NovaAppKinds::isSafeApp)
        val settings = NovaAppKinds.isSettings(context, reported) ||
            (reported == null && NovaAppKinds.isSettings(context, observed))
        val isHomeReported = NovaAppKinds.isCocoon(context, reported)
        val isHomeObserved = NovaAppKinds.isCocoon(context, observed)
        val home = !settings && (isHomeReported || (reported == null && isHomeObserved))
        val currentFrameFresh = SystemClock.elapsedRealtime() - frameTakenAt < 90000L
        val picturePackage = capturedPackage

        // UsageStats can lag for full-screen emulators. Prefer the latest actual
        // screenshot when Accessibility still identifies that emulator as current.
        val selected = when {
            settings -> reported ?: observed
            home -> reported ?: observed
            currentFrameFresh && picturePackage != null &&
                observed == picturePackage -> picturePackage
            reported != null -> reported
            else -> observed ?: picturePackage
        }
        val frame = if (!home && !settings && currentFrameFresh &&
            selected == picturePackage) lastBitmap else null
        frozen = Preview(
            selected, frame,
            home || NovaAppKinds.isCocoon(context, selected),
            settings || NovaAppKinds.isSettings(context, selected)
        )
        isLocked = true
    }

    @Synchronized fun preview(): Preview = frozen

    @Synchronized fun unfreeze() {
        if (!isLocked) return
        isLocked = false
        frozen = Preview(null, null)
        NovaCaptureService.resumeAfterUnlock()
    }
}
