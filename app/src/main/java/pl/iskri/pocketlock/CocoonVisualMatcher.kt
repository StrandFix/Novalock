package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline visual fingerprint matcher.
 *
 * Cocoon 3.1 shows the selected game's downloaded artwork during launch.
 * We can compare this visible picture with the user's opted-in Cocoon folder
 * without reading Cocoon's private database or intercepting another app's Intent.
 *
 * Results are deliberately conservative. A confident single winner is needed,
 * otherwise the existing live screenshot remains the lock-screen image.
 * No network, OCR, image persistence or root. All work is suspended in sleep.
 */
object CocoonVisualMatcher {
    data class ArtworkReference(val gameKey: String, val category: String, val uri: Uri)
    private data class Fingerprint(
        val gameKey: String, val category: String,
        val landscape: Long, val fourByThree: Long, val square: Long
    )

    private val lock = Any()
    private var signatures: List<Fingerprint> = emptyList()
    private val building = AtomicBoolean(false)
    private val matching = AtomicBoolean(false)
    @Volatile var report: String = "Visual artwork index not yet built"
        private set

    private const val MAX_VISUAL_ARTWORK = 3500
    private const val MAX_STRICT_DISTANCE = 15
    private const val MIN_WINNER_MARGIN = 6

    fun rebuild(context: Context, images: List<ArtworkReference>) {
        if (!building.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            val out = ArrayList<Fingerprint>()
            var failed = 0
            try {
                val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                for (image in images
                    .filter { it.category != "logo" }
                    .distinctBy { it.uri.toString() }
                    .take(MAX_VISUAL_ARTWORK)) {
                    if (!power.isInteractive || GamePreviewStore.isLocked) {
                        report = "Visual indexing paused because the device is sleeping"
                        break
                    }
                    try {
                        val optBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        app.contentResolver.openInputStream(image.uri)?.use {
                            BitmapFactory.decodeStream(it, null, optBounds)
                        }
                        if (optBounds.outWidth <= 0 || optBounds.outHeight <= 0) continue
                        val maxDimension = maxOf(optBounds.outWidth, optBounds.outHeight)
                        val divisor = (maxDimension / 130).coerceAtLeast(1)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = Integer.highestOneBit(divisor)
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bitmap = app.contentResolver.openInputStream(image.uri)?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        } ?: continue
                        try {
                            if (bitmap.width < 16 || bitmap.height < 16) continue
                            out.add(Fingerprint(
                                image.gameKey, image.category,
                                visualHash(bitmap, RectF(0f, 0f, 1f, 1f), 16f / 9f),
                                visualHash(bitmap, RectF(0f, 0f, 1f, 1f), 4f / 3f),
                                visualHash(bitmap, RectF(0f, 0f, 1f, 1f), 1f)
                            ))
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (_: Exception) { failed++ }
                }
                synchronized(lock) { signatures = out.toList() }
                report = "Visual index: ${out.size} image fingerprints; $failed unreadable"
            } finally {
                building.set(false)
            }
        }, "Novalock-Visual-Index").start()
    }

    /**
     * Caller owns and recycles the frame. Callback receives a verified key
     * only if one and only one game clearly matches the Cocoon screen.
     */
    fun matchFrame(
        context: Context,
        frame: Bitmap,
        onIdentified: (String, Int, Int) -> Unit
    ) {
        if (!matching.compareAndSet(false, true)) {
            frame.recycle()
            return
        }
        val signaturesCopy = synchronized(lock) { signatures }
        if (signaturesCopy.isEmpty()) {
            matching.set(false)
            frame.recycle()
            return
        }
        val app = context.applicationContext
        Thread({
            try {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive || GamePreviewStore.isLocked) return@Thread
                val windows = listOf(
                    RectF(0f, 0f, 1f, 1f),
                    RectF(.05f, .05f, .95f, .95f),
                    RectF(.12f, .04f, .88f, .88f),
                    RectF(0f, .06f, 1f, .77f),
                    RectF(.2f, .08f, .8f, .86f),
                    RectF(.26f, .14f, .74f, .86f)
                )
                val screen = windows.map { area ->
                    Triple(visualHash(frame, area, 16f / 9f),
                        visualHash(frame, area, 4f / 3f),
                        visualHash(frame, area, 1f))
                }
                val winners = HashMap<String, Int>()
                for (item in signaturesCopy) {
                    val distance = screen.minOf { hashes ->
                        minOf(
                            java.lang.Long.bitCount(hashes.first xor item.landscape),
                            java.lang.Long.bitCount(hashes.second xor item.fourByThree),
                            java.lang.Long.bitCount(hashes.third xor item.square)
                        )
                    }
                    val old = winners[item.gameKey]
                    if (old == null || distance < old) winners[item.gameKey] = distance
                }
                val sorted = winners.entries.sortedBy { it.value }
                val first = sorted.firstOrNull() ?: return@Thread
                val second = sorted.getOrNull(1)?.value ?: 64
                if (first.value <= MAX_STRICT_DISTANCE &&
                    second - first.value >= MIN_WINNER_MARGIN &&
                    pm.isInteractive && !GamePreviewStore.isLocked) {
                    report = "Unique visual match: ${first.key} score=${first.value}, next=$second"
                    onIdentified(first.key, first.value, second)
                } else {
                    report = "No reliable visual match (closest=${first.value}, next=$second)"
                }
            } catch (e: Exception) {
                report = "Visual comparison failed: ${e.javaClass.simpleName}"
                Log.d("NovaVisual", "Local visual match unavailable", e)
            } finally {
                frame.recycle()
                matching.set(false)
            }
        }, "Novalock-Visual-Match").start()
    }

    /** dHash remains largely invariant under resized/dimmed hero artwork. */
    private fun visualHash(bitmap: Bitmap, region: RectF, targetAspect: Float): Long {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val regionW = (region.width() * bw).coerceAtLeast(9f)
        val regionH = (region.height() * bh).coerceAtLeast(8f)
        val actualAspect = regionW / regionH
        val usedW: Float
        val usedH: Float
        if (actualAspect > targetAspect) {
            usedH = regionH
            usedW = regionH * targetAspect
        } else {
            usedW = regionW
            usedH = regionW / targetAspect
        }
        val startX = region.left * bw + (regionW - usedW) * .5f
        val startY = region.top * bh + (regionH - usedH) * .5f
        val gray = IntArray(9 * 8)
        for (row in 0 until 8) {
            for (col in 0 until 9) {
                val px = (startX + (col + .5f) * usedW / 9f)
                    .toInt().coerceIn(0, bitmap.width - 1)
                val py = (startY + (row + .5f) * usedH / 8f)
                    .toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(px, py)
                gray[row * 9 + col] =
                    (color shr 16 and 255) * 30 +
                    (color shr 8 and 255) * 59 +
                    (color and 255) * 11
            }
        }
        var bits = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                bits = bits shl 1
                if (gray[y * 9 + x] > gray[y * 9 + x + 1]) bits = bits or 1L
            }
        }
        return bits
    }
}
