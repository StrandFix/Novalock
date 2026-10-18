package pl.iskri.pocketlock

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.content.Context
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Optional service that takes periodic single screenshots (never records video).
 * Images are kept in process memory only and not transmitted or saved to disk.
 */
class NovaCaptureService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private val sampler = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val pkg = GamePreviewStore.currentPackage
                if (!busy && pm.isInteractive && !GamePreviewStore.isLocked &&
                    Prefs.isEnabled(this@NovaCaptureService) && pkg != null &&
                    pkg != packageName) {
                    busy = true
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                busy = false
                                try {
                                    val buffer = result.hardwareBuffer
                                    try {
                                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                        val mutableCopy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                        if (mutableCopy != null && !GamePreviewStore.isLocked) {
                                            val scale = minOf(1f,
                                                1024f / mutableCopy.width,
                                                1024f / mutableCopy.height)
                                            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
                                                mutableCopy,
                                                (mutableCopy.width * scale).toInt().coerceAtLeast(1),
                                                (mutableCopy.height * scale).toInt().coerceAtLeast(1),
                                                true)
                                            else mutableCopy
                                            GamePreviewStore.rememberFrame(pkg, scaled)
                                        }
                                    } finally { buffer.close() }
                                } catch (error: Throwable) {
                                    Log.w("NovaCapture", "Screenshot decode failed", error)
                                }
                            }
                            override fun onFailure(errorCode: Int) {
                                busy = false
                                Log.d("NovaCapture", "Screenshot unavailable: " + errorCode)
                            }
                        })
                }
            } catch (error: Throwable) {
                busy = false
                Log.w("NovaCapture", "Screenshot request failed", error)
            } finally {
                handler.postDelayed(this, 4000L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(sampler)
        handler.post(sampler)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val name = event.packageName?.toString() ?: return
            if (name != packageName && !name.startsWith("pl.iskri.pocketlock")) {
                GamePreviewStore.observeForeground(name)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(sampler)
        super.onDestroy()
    }
}
