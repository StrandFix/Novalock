package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device, BUNDLED text recognition of Cocoon's currently visible game
 * title. Never examines emulator gameplay, other apps, or the lock screen.
 * No network operation and no screenshots saved to disk.
 *
 * Cocoon's picture-based launcher sometimes exposes no Accessibility labels.
 * OCR gives an independent candidate from its rendered title/logo.
 */
object CocoonTitleRecognizer {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val busy = AtomicBoolean(false)
    @Volatile private var lastAttemptAt = 0L
    @Volatile var report = "OCR has not scanned Cocoon yet"
        private set

    fun scanCocoonFrame(context: Context, bitmap: Bitmap) {
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive || GamePreviewStore.isLocked ||
            Prefs.cocoonImageMode(app) == Prefs.COCOON_MODE_LIVE ||
            !NovaAppKinds.isCocoon(app, GamePreviewStore.currentPackage)) {
            bitmap.recycle()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptAt < 1500L || !busy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        lastAttemptAt = now
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (!pm.isInteractive || GamePreviewStore.isLocked ||
                        !NovaAppKinds.isCocoon(app, GamePreviewStore.currentPackage)) return@addOnSuccessListener
                    val labels = result.textBlocks.flatMap { block ->
                        buildList {
                            add(block.text)
                            block.lines.forEach { add(it.text) }
                        }
                    }.distinct().filter { it.isNotBlank() }
                    report = if (labels.isEmpty()) "No visible Cocoon game-title text read"
                        else "Read ${labels.size} text candidates (title match checked)"
                    CocoonArtworkStore.observeVisibleTitle(app, labels)
                }
                .addOnFailureListener { e ->
                    report = "Offline OCR unavailable: ${e.javaClass.simpleName}"
                }
                .addOnCompleteListener {
                    bitmap.recycle()
                    busy.set(false)
                }
        } catch (e: Exception) {
            report = "Offline OCR could not start: ${e.javaClass.simpleName}"
            bitmap.recycle()
            busy.set(false)
        }
    }
}
