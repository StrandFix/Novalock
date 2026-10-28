package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Standalone V6.10 prototype. Compares ONLY the actually focused Cocoon
 * game-card rectangle, not the entire HOME grid, with locally approved art.
 *
 * This remains experimental: custom-drawn UIs may report only fullscreen
 * focus bounds, or the selected card can be visually different from stored
 * art. Ambiguous / non-card / unconfirmed matches are always rejected.
 */
object CocoonCardMatcher {
    data class ArtworkRef(val gameKey: String, val category: String, val uri: Uri)
    private data class Descriptor(
        val gameKey: String,
        val category: String,
        val pixels: IntArray,
        val variance: Int
    )
    data class Result(val gameKey: String, val score: Int, val secondScore: Int)
    private val guard = Any()
    private val building = AtomicBoolean(false)
    private val matching = AtomicBoolean(false)
    private var refs: List<ArtworkRef> = emptyList()
    private var fingerprints: List<Descriptor> = emptyList()
    @Volatile private var complete = false
    @Volatile var status: String = "Selected-card image index not prepared"
        private set
    @Volatile var lastMatch: String = "No focused card captured"
        private set

    private const val GRID = 18
    private const val MAX_IMAGES = 3500
    // Mean RGB error across 18x18 pixels. Require a clear winner over the
    // SECOND DISTINCT GAME, never a match merely to any similarly colored
    // platform icon.
    private const val MAX_ERROR = 30
    private const val MIN_MARGIN = 9

    fun rebuild(context: Context, images: List<ArtworkRef>) {
        val unique = images.distinctBy { it.uri.toString() }.take(MAX_IMAGES)
        synchronized(guard) {
            refs = unique
            complete = false
            fingerprints = emptyList()
        }
        startBuild(context)
    }

    fun ensureBuilt(context: Context) {
        if (!complete) startBuild(context)
    }

    private fun startBuild(context: Context) {
        if (!building.compareAndSet(false, true)) return
        val app = context.applicationContext
        val available = synchronized(guard) { refs.toList() }
        if (available.isEmpty()) {
            building.set(false)
            return
        }
        Thread({
            val found = ArrayList<Descriptor>()
            var failed = 0
            var interrupted = false
            try {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                for (art in available) {
                    if (!pm.isInteractive || GamePreviewStore.isLocked) {
                        interrupted = true
                        break
                    }
                    if (CocoonArtworkStore.isPlatformKey(art.gameKey)) continue
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        app.contentResolver.openInputStream(art.uri)?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                        if (bounds.outWidth < 20 || bounds.outHeight < 20) continue
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 160 &&
                            sample < 128) sample *= 2
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val source = app.contentResolver.openInputStream(art.uri)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        } ?: continue
                        try {
                            val pixels = downsample(source, Rect(0, 0, source.width, source.height))
                            val variance = variance(pixels)
                            if (variance >= 18) {
                                found.add(Descriptor(art.gameKey, art.category, pixels, variance))
                            }
                        } finally {
                            source.recycle()
                        }
                    } catch (_: Exception) { failed++ }
                }
                synchronized(guard) {
                    fingerprints = found.toList()
                    complete = !interrupted
                    status = if (interrupted)
                        "Card index paused at ${found.size} images; restarts when awake"
                    else "Card index ready: ${found.size} visual samples, $failed unreadable"
                }
            } catch (e: Exception) {
                status = "Card index failed: ${e.javaClass.simpleName}"
                Log.d("NovalockCard", "Local index", e)
            } finally {
                building.set(false)
            }
        }, "Novalock-Card-Index").start()
    }

    /**
     * Caller transfers ownership of frame. Callback runs off-main-thread;
     * never persist or upload screenshot/cropped pixels.
     */
    fun matchFocusedCard(
        context: Context, frame: Bitmap, focused: Rect,
        displayWidth: Int, displayHeight: Int,
        onResult: (Result) -> Unit
    ) {
        if (!matching.compareAndSet(false, true)) {
            frame.recycle()
            return
        }
        val index = synchronized(guard) {
            if (complete) fingerprints.toList() else emptyList()
        }
        if (index.isEmpty()) {
            lastMatch = if (building.get()) "Card image index still building"
                else status
            frame.recycle()
            matching.set(false)
            return
        }
        val app = context.applicationContext
        Thread({
            try {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive || GamePreviewStore.isLocked) return@Thread
                val scaleX = frame.width.toFloat() / displayWidth.coerceAtLeast(1)
                val scaleY = frame.height.toFloat() / displayHeight.coerceAtLeast(1)
                val bounds = Rect(
                    (focused.left * scaleX).toInt().coerceIn(0, frame.width),
                    (focused.top * scaleY).toInt().coerceIn(0, frame.height),
                    (focused.right * scaleX).toInt().coerceIn(0, frame.width),
                    (focused.bottom * scaleY).toInt().coerceIn(0, frame.height)
                )
                if (bounds.width() < 32 || bounds.height() < 32 ||
                    bounds.width() * bounds.height() > frame.width.toLong() *
                        frame.height.toLong() * 42L / 100L) {
                    lastMatch = "Focused node covers unsuitable screen area: $bounds"
                    return@Thread
                }

                // Card-image regions often occupy only the upper/middle
                // portion of a focused card containing labels and borders.
                // Never scan arbitrary tiles outside the focused bounds.
                val crops = listOf(
                    RectF(.03f, .03f, .97f, .97f),
                    RectF(.08f, .07f, .92f, .88f),
                    RectF(.06f, .02f, .94f, .72f),
                    RectF(.16f, .07f, .84f, .91f),
                    RectF(.12f, .12f, .88f, .88f),
                    RectF(0f, 0f, 1f, 1f)
                )
                val visible = crops.mapNotNull { crop ->
                    val rect = Rect(
                        (bounds.left + crop.left * bounds.width()).toInt(),
                        (bounds.top + crop.top * bounds.height()).toInt(),
                        (bounds.left + crop.right * bounds.width()).toInt(),
                        (bounds.top + crop.bottom * bounds.height()).toInt()
                    )
                    if (rect.width() < 24 || rect.height() < 24) null
                    else downsample(frame, rect)
                }.filter { variance(it) >= 18 }
                if (visible.isEmpty()) {
                    lastMatch = "Focused node has no distinctive image texture"
                    return@Thread
                }
                val grouped = HashMap<String, Int>()
                for (candidate in index) {
                    val score = visible.minOf { sample ->
                        colorError(sample, candidate.pixels)
                    }
                    val prior = grouped[candidate.gameKey]
                    if (prior == null || score < prior) grouped[candidate.gameKey] = score
                }
                val ranked = grouped.entries.sortedWith(
                    compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                val first = ranked.firstOrNull() ?: return@Thread
                val second = ranked.getOrNull(1)
                val runnerScore = second?.value ?: 255
                lastMatch = "Focused card ${focused.flattenToString()}: " +
                    "best=${first.key} (${first.value}), " +
                    "next=${second?.key ?: "none"} ($runnerScore)"
                if (first.value <= MAX_ERROR &&
                    runnerScore - first.value >= MIN_MARGIN &&
                    pm.isInteractive && !GamePreviewStore.isLocked) {
                    onResult(Result(first.key, first.value, runnerScore))
                } else {
                    lastMatch += " — rejected (not distinctive enough)"
                }
            } catch (e: Exception) {
                lastMatch = "Card comparison failed: ${e.javaClass.simpleName}"
                Log.d("NovalockCard", "Card comparison", e)
            } finally {
                frame.recycle()
                matching.set(false)
            }
        }, "Novalock-Card-Match").start()
    }

    /** Downsample a rectangular area into a tiny, disposal-safe RGB vector. */
    private fun downsample(bitmap: Bitmap, region: Rect): IntArray {
        val out = IntArray(GRID * GRID * 3)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val px = (region.left + (x + .5f) * region.width() / GRID)
                    .toInt().coerceIn(0, bitmap.width - 1)
                val py = (region.top + (y + .5f) * region.height() / GRID)
                    .toInt().coerceIn(0, bitmap.height - 1)
                val argb = bitmap.getPixel(px, py)
                val i = (y * GRID + x) * 3
                out[i] = (argb shr 16) and 255
                out[i + 1] = (argb shr 8) and 255
                out[i + 2] = argb and 255
            }
        }
        return out
    }

    private fun variance(pixels: IntArray): Int {
        var avg = 0L
        for (v in pixels) avg += v
        val mean = (avg / pixels.size).toInt()
        var sum = 0L
        for (v in pixels) sum += abs(v - mean)
        return (sum / pixels.size).toInt()
    }

    private fun colorError(a: IntArray, b: IntArray): Int {
        var total = 0L
        for (i in a.indices) total += abs(a[i] - b[i])
        return (total / a.size).toInt()
    }
}
