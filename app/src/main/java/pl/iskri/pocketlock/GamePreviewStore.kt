package pl.iskri.pocketlock

import android.graphics.Bitmap

/** A process-local, short-lived screenshot. Nothing is written to disk. */
object GamePreviewStore {
    data class Preview(val packageName: String?, val bitmap: Bitmap?)

    @Volatile var isLocked: Boolean = false
        private set
    @Volatile var currentPackage: String? = null
        private set

    private var lastBitmap: Bitmap? = null
    private var capturedPackage: String? = null
    private var frozen = Preview(null, null)

    @Synchronized fun observeForeground(name: String) {
        if (!isLocked && !name.startsWith("pl.iskri.pocketlock")) {
            currentPackage = name
        }
    }

    @Synchronized fun rememberFrame(name: String, bitmap: Bitmap) {
        if (isLocked || currentPackage != name) return
        lastBitmap = bitmap
        capturedPackage = name
    }

    @Synchronized fun freeze(name: String?) {
        val selected = name ?: currentPackage
        frozen = Preview(selected, if (selected != null && selected == capturedPackage) lastBitmap else null)
        isLocked = true
    }

    @Synchronized fun preview(): Preview = frozen

    @Synchronized fun unfreeze() {
        isLocked = false
        frozen = Preview(null, null)
    }
}
