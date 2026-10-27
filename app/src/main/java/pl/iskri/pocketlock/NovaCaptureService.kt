package pl.iskri.pocketlock

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Battery-conscious, optional game screenshot capture.
 * While a game is foregrounded: one still frame every 25/11/6 seconds as configured.
 * Screen off / locked / Cocoon / disabled: no screenshot polling.
 * All images remain in RAM; none are written to disk.
 */
class NovaCaptureService : AccessibilityService() {
    companion object {
        private const val APP_SWITCH_DELAY_MS = 1_600L
        private const val MAX_PREVIEW_EDGE_PX = 760f

        @Volatile var isConnected: Boolean = false
            private set
        private var instance: WeakReference<NovaCaptureService>? = null

        fun resumeAfterUnlock() {
            instance?.get()?.scheduleCapture(2200L)
        }

        fun onCapturePreferenceChanged() {
            instance?.get()?.let { service ->
                if (Prefs.isCaptureEnabled(service)) service.scheduleCapture(1400L)
                else service.handler.removeCallbacks(service.sampler)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var receiver: BroadcastReceiver? = null
    private val sampler = Runnable { captureIfReady() }

    private fun scheduleCapture(afterMs: Long) {
        handler.removeCallbacks(sampler)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive && !GamePreviewStore.isLocked &&
            Prefs.isEnabled(this) && Prefs.isCaptureEnabled(this) &&
            !NovaAppKinds.isCocoon(this, GamePreviewStore.currentPackage) &&
            !NovaAppKinds.isSettings(this, GamePreviewStore.currentPackage)) {
            handler.postDelayed(sampler, afterMs)
        }
    }

    private fun awakeAndPlaying(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive && !GamePreviewStore.isLocked &&
            Prefs.isEnabled(this) && Prefs.isCaptureEnabled(this)
    }

    private fun latestUsageForeground(): String? = try {
        val now = System.currentTimeMillis()
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(now - 120_000L, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val candidate = event.packageName
                if (NovaAppKinds.isSafeApp(candidate)) latest = candidate
            }
        }
        latest
    } catch (_: Throwable) { null }

    private fun captureIfReady() {
        if (!awakeAndPlaying()) return
        val usagePackage = latestUsageForeground()
        if (usagePackage != null) GamePreviewStore.observeForeground(this, usagePackage)
        val target = GamePreviewStore.currentPackage
        if (target != null && !NovaAppKinds.isCocoon(this, target) &&
            !NovaAppKinds.isSettings(this, target) && !busy) {
            busy = true
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            busy = false
                            try {
                                val buffer = result.hardwareBuffer
                                try {
                                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                    if (copy != null) {
                                        val scale = minOf(
                                            1f, MAX_PREVIEW_EDGE_PX / copy.width,
                                            MAX_PREVIEW_EDGE_PX / copy.height)
                                        val scaled = if (scale < 1f)
                                            Bitmap.createScaledBitmap(
                                                copy,
                                                (copy.width * scale).toInt().coerceAtLeast(1),
                                                (copy.height * scale).toInt().coerceAtLeast(1),
                                                true)
                                        else copy
                                        if (scaled !== copy) copy.recycle()
                                        if (awakeAndPlaying()) {
                                            GamePreviewStore.rememberFrame(target, scaled)
                                        } else {
                                            scaled.recycle()
                                        }
                                    }
                                } finally {
                                    buffer.close()
                                }
                            } catch (e: Throwable) {
                                Log.w("NovaCapture", "Screenshot decode failed", e)
                                GamePreviewStore.noteCaptureFailure(-1)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            busy = false
                            GamePreviewStore.noteCaptureFailure(errorCode)
                            Log.d("NovaCapture", "Capture blocked: $errorCode")
                        }
                    })
            } catch (e: Throwable) {
                busy = false
                GamePreviewStore.noteCaptureFailure(-2)
                Log.w("NovaCapture", "Capture request failed", e)
            }
        }
        // No recurring work is scheduled when the display is off or locked.
        if (awakeAndPlaying()) scheduleCapture(Prefs.captureIntervalMs(this))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        instance = WeakReference(this)
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(sampler)
                    Intent.ACTION_SCREEN_ON -> if (!GamePreviewStore.isLocked)
                        scheduleCapture(APP_SWITCH_DELAY_MS)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        receiver = screenReceiver
        scheduleCapture(APP_SWITCH_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (!NovaAppKinds.isSafeApp(pkg)) return
        if (NovaAppKinds.isCocoon(this, pkg) &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
             event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
            // Only read labels on game-selection events originating in Cocoon.
            // No Android window content traversal and no data transmission.
            CocoonArtworkStore.observeCocoonSelection(
                this, event.text.map { it.toString() })
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val previous = GamePreviewStore.currentPackage
        GamePreviewStore.observeForeground(this, pkg)
        if (NovaAppKinds.isCocoon(this, pkg) ||
            NovaAppKinds.isSettings(this, pkg)) {
            // Neither Home nor Settings needs a screenshot or a pending timer.
            handler.removeCallbacks(sampler)
        } else if (previous != GamePreviewStore.currentPackage && awakeAndPlaying()) {
            scheduleCapture(APP_SWITCH_DELAY_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        isConnected = false
        handler.removeCallbacks(sampler)
        receiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        instance = null
        super.onDestroy()
    }
}
