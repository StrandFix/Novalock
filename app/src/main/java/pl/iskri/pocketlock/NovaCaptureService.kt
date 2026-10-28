package pl.iskri.pocketlock

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var focusedCaptureBusy = false
    private var cardEpoch = 0L
    private var cardFocusId = 0L
    private var focusedCardTask: Runnable? = null
    private var lastFocusedBounds = Rect()
    private var lastFocusedAt = 0L

    private fun captureFocusedCocoonCard(event: AccessibilityEvent) {
        cardFocusId += 1L
        val focusId = cardFocusId
        if (Prefs.cocoonImageMode(this) == Prefs.COCOON_MODE_LIVE ||
            !Prefs.isEnabled(this) || !CocoonArtworkStore.selectedFolder(this) ||
            GamePreviewStore.isLocked) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        val realSize = Point()
        @Suppress("DEPRECATION")
        val screen = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        screen.getRealSize(realSize)
        if (realSize.x < 100 || realSize.y < 100) return

        val source = try { event.source } catch (_: Exception) { null }
        val focusedRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        val candidates = ArrayList<AccessibilityNodeInfo>()
        if (source != null) candidates.add(source)
        if (source != null) {
            try { source.parent } catch (_: Exception) { null }?.let(candidates::add)
        }
        if (focusedRoot != null &&
            NovaAppKinds.isCocoon(this, focusedRoot.packageName?.toString())) {
            try { focusedRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                catch (_: Exception) { null }?.let(candidates::add)
            try { focusedRoot.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }
                catch (_: Exception) { null }?.let(candidates::add)
        }
        fun sensibleBounds(rect: Rect): Boolean {
            val w = rect.width()
            val h = rect.height()
            if (w < 56 || h < 56) return false
            if (rect.left < 0 || rect.top < 0 ||
                rect.right > realSize.x || rect.bottom > realSize.y) return false
            val area = w.toLong() * h.toLong()
            val displayArea = realSize.x.toLong() * realSize.y.toLong()
            return area < displayArea * 40L / 100L &&
                w <= realSize.x * 75L / 100L &&
                h <= realSize.y * 80L / 100L
        }
        val bounds = Rect()
        var valid = false
        val labels = LinkedHashSet<String>()
        event.text.forEach { it.toString().trim().takeIf(String::isNotEmpty)?.let(labels::add) }
        for (node in candidates) {
            if (!NovaAppKinds.isCocoon(this, node.packageName?.toString())) continue
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(labels::add)
            node.contentDescription?.toString()?.trim()
                ?.takeIf(String::isNotEmpty)?.let(labels::add)
            val rect = Rect()
            try { node.getBoundsInScreen(rect) } catch (_: Exception) { continue }
            if (sensibleBounds(rect)) {
                bounds.set(rect)
                valid = true
                break
            }
        }
        // A genuine game title exposed on the SELECTED node is preferred,
        // but this was absent in earlier Retroid Pocket Nova diagnostics.
        if (labels.isNotEmpty()) {
            CocoonArtworkStore.observeCocoonGameSelection(
                this, AccessibilityEvent.eventTypeToString(event.eventType),
                labels.toList())
        }
        val note = if (valid) "focused bounded card; checking pixels"
            else "No card-sized node (possible fullscreen Cocoon surface)"
        CocoonArtworkStore.noteFocusedBounds(
            AccessibilityEvent.eventTypeToString(event.eventType),
            if (valid) bounds else null, note)
        if (!valid) return
        CocoonCardMatcher.ensureBuilt(this)

        cardEpoch += 1L
        val epoch = cardEpoch
        val rectCopy = Rect(bounds)
        lastFocusedBounds = rectCopy
        lastFocusedAt = android.os.SystemClock.elapsedRealtime()
        focusedCardTask?.let(handler::removeCallbacks)
        // Screenshot only for genuine focused Cocoon card, not a repeating
        // HOME poll. Wait one frame for controller selection to settle.
        val task = Runnable {
            if (focusedCaptureBusy || GamePreviewStore.isLocked || !pm.isInteractive ||
                cardEpoch != epoch) return@Runnable
            val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
            if (!NovaAppKinds.isCocoon(this, activeRoot?.packageName?.toString())) {
                CocoonArtworkStore.noteFocusedBounds(
                    "CAPTURE", rectCopy, "Rejected: active window is not Cocoon")
                return@Runnable
            }
            focusedCaptureBusy = true
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            focusedCaptureBusy = false
                            val buffer = result.hardwareBuffer
                            try {
                                if (cardEpoch != epoch || GamePreviewStore.isLocked ||
                                    !pm.isInteractive) return
                                val stillRoot = try { rootInActiveWindow }
                                    catch (_: Exception) { null }
                                if (!NovaAppKinds.isCocoon(
                                        this@NovaCaptureService,
                                        stillRoot?.packageName?.toString())) {
                                    CocoonArtworkStore.noteFocusedBounds(
                                        "CAPTURE", rectCopy,
                                        "Discarded frame: Cocoon no longer foreground")
                                    return
                                }
                                val capturedAt = android.os.SystemClock.elapsedRealtime()
                                val hardware = Bitmap.wrapHardwareBuffer(
                                    buffer, result.colorSpace)
                                val frame = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: return
                                CocoonCardMatcher.matchFocusedCard(
                                    this@NovaCaptureService, frame, rectCopy,
                                    realSize.x, realSize.y) { match ->
                                    val pkg = GamePreviewStore.currentPackage
                                    val recentGameLaunch =
                                        pkg != null &&
                                        NovaAppKinds.isLikelyEmulator(
                                            this@NovaCaptureService, pkg) &&
                                        android.os.SystemClock.elapsedRealtime() -
                                            capturedAt < 4000L
                                    if (cardFocusId == focusId &&
                                        (cardEpoch == epoch || recentGameLaunch) &&
                                        !GamePreviewStore.isLocked) {
                                        CocoonArtworkStore.observeFocusedGameImage(
                                            this@NovaCaptureService, match.gameKey,
                                            match.score, match.secondScore, capturedAt)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d("NovaCard", "Focused Cocoon capture failed", e)
                            } finally {
                                buffer.close()
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            focusedCaptureBusy = false
                            CocoonArtworkStore.noteFocusedBounds(
                                "CAPTURE", rectCopy, "Android screenshot error=$errorCode")
                        }
                    })
            } catch (e: Exception) {
                focusedCaptureBusy = false
                CocoonArtworkStore.noteFocusedBounds(
                    "CAPTURE", rectCopy, "Capture failed: ${e.javaClass.simpleName}")
            }
        }
        focusedCardTask = task
        handler.postDelayed(task, 95L)
    }

    private fun invalidateCardCapture() {
        cardEpoch += 1L
        focusedCardTask?.let(handler::removeCallbacks)
        focusedCardTask = null
    }
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
                    Intent.ACTION_SCREEN_OFF -> {
                        handler.removeCallbacks(sampler)
                        invalidateCardCapture()
                    }
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
        CocoonArtworkStore.ensureIndexed(this)
        scheduleCapture(APP_SWITCH_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (!NovaAppKinds.isSafeApp(pkg)) return
        if (NovaAppKinds.isCocoon(this, pkg)) {
            CocoonArtworkStore.ensureIndexed(this)
            val focusEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            if (focusEvent) captureFocusedCocoonCard(event)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            invalidateCardCapture()
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // The emulator's actual game/window title is our source of truth.
        CocoonArtworkStore.observeEmulatorWindowTitle(
            this, pkg, event.text.map { it.toString() })
        val previous = GamePreviewStore.currentPackage
        GamePreviewStore.observeForeground(this, pkg)
        // No HOME screenshot scanning: only running-game previews.
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
        invalidateCardCapture()
        receiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        instance = null
        super.onDestroy()
    }
}
