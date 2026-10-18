package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import android.widget.ImageView.ScaleType
import android.widget.LinearLayout
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object LockAppearance {

    const val BACKGROUND_FILE = "lock_bg.img"
    private const val LEGACY_BACKGROUND_FILE = "lock_bg.jpg"

    private var cachedBitmap: Bitmap? = null

    fun backgroundFile(context: Context): File = File(context.filesDir, BACKGROUND_FILE)

    private fun existingBackgroundFile(context: Context): File? {
        val file = backgroundFile(context)
        if (file.exists()) return file
        val legacy = File(context.filesDir, LEGACY_BACKGROUND_FILE)
        if (legacy.exists()) return legacy
        return null
    }

    fun deleteBackground(context: Context) {
        try {
            backgroundFile(context).delete()
        } catch (_: Exception) {
        }
        try {
            File(context.filesDir, LEGACY_BACKGROUND_FILE).delete()
        } catch (_: Exception) {
        }
    }

    fun invalidateCache() {
        cachedBitmap = null
    }

    fun apply(root: View, context: Context) {
        val background = root.findViewById<ImageView>(R.id.bg_image)
        val dots = root.findViewById<LinearLayout>(R.id.dots_container)
        val topBar = root.findViewById<LockTopBar>(R.id.top_bar)
        if (background != null) {
            applyBackground(background, context, root.width, root.height)
        }
        if (dots != null) {
            applyDots(dots, context, root.width, root.height)
        }
        topBar?.applyAppearance()
        applyNovaPreview(root, context)
    }

    private fun applyNovaPreview(root: View, context: Context) {
        val frame = root.findViewById<FrameLayout>(R.id.preview_frame) ?: return
        val image = root.findViewById<ImageView>(R.id.preview_image) ?: return
        val headline = root.findViewById<TextView>(R.id.nova_instruction) ?: return
        val subtitle = root.findViewById<TextView>(R.id.nova_subtitle) ?: return
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return

        val snapshot = GamePreviewStore.preview()
        val bmp = snapshot.bitmap
        if (bmp != null && !bmp.isRecycled) {
            image.setImageBitmap(bmp)
            image.scaleType = ScaleType.FIT_CENTER
            image.setPadding(0, 0, 0, 0)
        } else {
            val fallback = try {
                snapshot.packageName?.let { context.packageManager.getApplicationIcon(it) }
            } catch (_: Exception) { null }
            image.setImageDrawable(fallback ?: context.getDrawable(android.R.drawable.sym_def_app_icon))
            image.scaleType = ScaleType.FIT_CENTER
        }

        val aspect = if (bmp != null && !bmp.isRecycled) bmp.width.toFloat() / bmp.height else 4f / 3f
        val frameW = minOf(w * .76f, h * .55f * aspect).toInt().coerceAtLeast(1)
        val frameH = (frameW / aspect).toInt().coerceAtLeast(1)
        val top = (h * .345f - frameH / 2f).toInt().coerceAtLeast(dp(context, 12f))
        frame.layoutParams = FrameLayout.LayoutParams(frameW, frameH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = top
        }
        if (bmp == null) {
            val padX = (frameW * .29f).toInt()
            val padY = (frameH * .24f).toInt()
            image.setPadding(padX, padY, padX, padY)
        }

        val titleHeight = (h * .085f).toInt().coerceAtLeast(dp(context, 50f))
        headline.layoutParams = FrameLayout.LayoutParams((w * .94f).toInt(), titleHeight,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (h * .685f).toInt() }
        headline.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(w * .038f, h * .057f))
        subtitle.layoutParams = FrameLayout.LayoutParams((w * .94f).toInt(),
            (h * .07f).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (h * .775f).toInt()
        }
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(w * .023f, h * .034f))
    }

    private fun applyBackground(
        background: ImageView,
        context: Context,
        rootW: Int,
        rootH: Int
    ) {
        val bitmap = loadBackground(context)
        if (bitmap == null) {
            background.visibility = View.GONE
            background.setImageDrawable(null)
            return
        }
        background.visibility = View.VISIBLE
        val current = (background.drawable as? BitmapDrawable)?.bitmap
        if (current !== bitmap) {
            background.setImageBitmap(bitmap)
        }
        if (rootW <= 0 || rootH <= 0) return
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        if (bitmapW <= 0f || bitmapH <= 0f) return
        val cover = max(rootW / bitmapW, rootH / bitmapH)
        val scale = cover * Prefs.backgroundScale(context)
        val drawnW = bitmapW * scale
        val drawnH = bitmapH * scale
        val overflowX = max(0f, (drawnW - rootW) / 2f)
        val overflowY = max(0f, (drawnH - rootH) / 2f)
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (rootW - drawnW) / 2f + Prefs.backgroundOffsetX(context) * overflowX,
            (rootH - drawnH) / 2f + Prefs.backgroundOffsetY(context) * overflowY
        )
        background.imageMatrix = matrix
    }

    fun backgroundPanRangePx(root: View, context: Context): Pair<Float, Float> {
        val background = root.findViewById<ImageView>(R.id.bg_image) ?: return 0f to 0f
        return backgroundPanRangePx(background, context, root.width, root.height)
    }

    private fun backgroundPanRangePx(
        background: ImageView,
        context: Context,
        rootW: Int,
        rootH: Int
    ): Pair<Float, Float> {
        val drawable = background.drawable ?: return 0f to 0f
        val imageW = drawable.intrinsicWidth.toFloat()
        val imageH = drawable.intrinsicHeight.toFloat()
        if (rootW <= 0 || rootH <= 0 || imageW <= 0f || imageH <= 0f) return 0f to 0f
        val userScale = Prefs.backgroundScale(context)
        val cover = max(rootW / imageW, rootH / imageH)
        val drawnW = imageW * cover * userScale
        val drawnH = imageH * cover * userScale
        return max(0f, (drawnW - rootW) / 2f) to max(0f, (drawnH - rootH) / 2f)
    }

    private fun applyDots(dots: LinearLayout, context: Context, rootW: Int, rootH: Int) {
        val dotScale = Prefs.dotScale(context)
        val spacing = Prefs.dotSpacing(context)
        val size = dp(context, 30f * dotScale)
        val margin = dp(context, 16f * dotScale * spacing)
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            if (lp.width != size || lp.height != size ||
                lp.leftMargin != margin || lp.rightMargin != margin) {
                lp.width = size
                lp.height = size
                lp.leftMargin = margin
                lp.rightMargin = margin
                dot.layoutParams = lp
            }
        }
        if (rootW > 0 && rootH > 0) {
            dots.translationX = (Prefs.dotCenterX(context) - 0.5f) * rootW
            dots.translationY = (Prefs.dotCenterY(context) - 0.5f) * rootH
        }
    }

    fun loadBackground(context: Context): Bitmap? {
        cachedBitmap?.let { if (!it.isRecycled) return it }
        val file = if (Prefs.isBackgroundEnabled(context)) existingBackgroundFile(context) else null
        val bitmap = if (file != null) decodeFile(context, file) else null
        cachedBitmap = bitmap
        return bitmap
    }

    private fun decodeFile(context: Context, file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION ||
                bounds.outHeight / sample > MAX_DIMENSION) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Throwable) {
            null
        }
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).roundToInt()

    private const val MAX_DIMENSION = 4096
}
