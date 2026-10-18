package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Single canvas layer: screenshots, card and text can never be occluded by
 * accidentally overlapping Android child views. The three original button
 * indicators remain ordinary ImageViews on top of this layer.
 */
class NovaLockContentView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val framePath = Path()
    private var iconFor: String? = null
    private var cachedIcon: Drawable? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val preview = GamePreviewStore.preview()
        val picture = preview.bitmap?.takeUnless { it.isRecycled }
        val aspect = picture?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        val cardW = min(w * .79f, h * .47f * aspect)
        val cardH = cardW / aspect
        val left = (w - cardW) / 2f
        val top = (h * .355f) - cardH / 2f
        val box = RectF(left, top, left + cardW, top + cardH)
        val corner = min(w, h) * .022f

        // Gentle shadow, dark material and subtle teal outline.
        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(RectF(box.left - 8f, box.top + 10f,
            box.right + 8f, box.bottom + 18f), corner * 1.3f, corner * 1.3f, p)
        p.color = Color.rgb(21, 27, 34)
        canvas.drawRoundRect(box, corner, corner, p)

        val inner = RectF(box.left + 4f, box.top + 4f,
            box.right - 4f, box.bottom - 4f)
        framePath.reset()
        framePath.addRoundRect(inner, corner * .85f, corner * .85f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(framePath)
        if (picture != null) {
            p.color = Color.WHITE
            val sourceAspect = picture.width.toFloat() / picture.height
            var dst = RectF(inner)
            if (sourceAspect > inner.width() / inner.height()) {
                val scaledH = inner.width() / sourceAspect
                dst = RectF(inner.left, inner.centerY() - scaledH / 2f,
                    inner.right, inner.centerY() + scaledH / 2f)
            } else {
                val scaledW = inner.height() * sourceAspect
                dst = RectF(inner.centerX() - scaledW / 2f, inner.top,
                    inner.centerX() + scaledW / 2f, inner.bottom)
            }
            canvas.drawBitmap(picture, null, dst, p)
        } else {
            val pkg = preview.packageName
            if (pkg != iconFor || cachedIcon == null) {
                iconFor = pkg
                cachedIcon = try {
                    pkg?.let { context.packageManager.getApplicationIcon(it) }
                } catch (_: Exception) {
                    null
                } ?: context.getDrawable(android.R.drawable.sym_def_app_icon)
            }
            val icon = cachedIcon
            if (icon != null) {
                val size = min(inner.width(), inner.height()) * .39f
                val cx = inner.centerX()
                val cy = inner.centerY()
                icon.setBounds((cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                    (cx + size / 2f).toInt(), (cy + size / 2f).toInt())
                icon.draw(canvas)
            }
        }
        canvas.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = (min(w, h) * .0028f).coerceAtLeast(2f)
        p.color = Color.rgb(85, 170, 175)
        canvas.drawRoundRect(box, corner, corner, p)
        p.style = Paint.Style.FILL

        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.color = Color.rgb(243, 247, 249)
        p.textSize = h * .046f
        val instruction = "Press the same button three times."
        val measured = p.measureText(instruction)
        if (measured > w * .91f) p.textSize *= (w * .91f / measured)
        canvas.drawText(instruction, w / 2f, h * .755f, p)

        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.color = Color.rgb(173, 186, 194)
        p.textSize = h * .027f
        canvas.drawText("Unlock to resume your game.", w / 2f, h * .825f, p)
    }
}
