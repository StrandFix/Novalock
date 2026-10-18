package pl.iskri.pocketlock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/** Compact, sharp 4:3 lock screen UI independent of Cocoon or game resolution. */
class NovaLockContentView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clippingPath = Path()
    private var iconFor: String? = null
    private var cachedIcon: Drawable? = null
    private var floating: ValueAnimator? = null
    private var floatFraction = 0f

    /**
     * Runs only while the actual lock screen is awake. Never starts on attach,
     * because the full-screen overlay is deliberately created while sleeping.
     */
    fun setDisplayAwake(awake: Boolean) {
        val preview = GamePreviewStore.preview()
        if (!awake || !isAttachedToWindow || !Prefs.isFloatEnabled(context) ||
            preview.isHome || preview.bitmap == null) {
            stopFloating()
            return
        }
        if (floating?.isRunning == true) return
        floating = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                floatFraction = (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    private fun stopFloating() {
        floating?.removeAllUpdateListeners()
        floating?.cancel()
        floating = null
        if (floatFraction != 0f) {
            floatFraction = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        stopFloating()
        super.onDetachedFromWindow()
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val snapshot = GamePreviewStore.preview()
        if (snapshot.isHome) {
            drawHomeMenu(canvas, w, h)
        } else {
            canvas.save()
            // Apply motion to the preview card only, not unlock text or dots.
            canvas.translate(0f, floatFraction * resources.displayMetrics.density * 7f)
            drawGamePreview(canvas, w, h, snapshot)
            canvas.restore()
        }

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
        val subtitle = if (snapshot.isHome) "Unlock to return to HOME Menu."
            else "Unlock to resume your game."
        canvas.drawText(subtitle, w / 2f, h * .825f, p)
    }

    private fun drawHomeMenu(canvas: Canvas, w: Float, h: Float) {
        // Original white HOME symbol, no screenshot and no Cocoon app logo.
        val centerX = w / 2f
        val centerY = h * .347f
        val radius = min(w, h) * .112f
        p.style = Paint.Style.FILL
        p.color = Color.argb(90, 9, 15, 21)
        canvas.drawCircle(centerX, centerY + 7f, radius + 8f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = min(w, h) * .0045f
        p.color = Color.rgb(231, 240, 243)
        canvas.drawCircle(centerX, centerY, radius, p)
        p.style = Paint.Style.FILL

        // Geometric house roof and walls with a small dark door.
        val house = Path().apply {
            moveTo(centerX - radius * .48f, centerY - radius * .03f)
            lineTo(centerX, centerY - radius * .49f)
            lineTo(centerX + radius * .48f, centerY - radius * .03f)
            lineTo(centerX + radius * .34f, centerY - radius * .03f)
            lineTo(centerX + radius * .34f, centerY + radius * .44f)
            lineTo(centerX - radius * .34f, centerY + radius * .44f)
            lineTo(centerX - radius * .34f, centerY - radius * .03f)
            close()
        }
        p.color = Color.rgb(239, 246, 247)
        canvas.drawPath(house, p)
        p.color = Color.rgb(25, 36, 42)
        canvas.drawRoundRect(RectF(centerX - radius * .105f, centerY + radius * .09f,
            centerX + radius * .105f, centerY + radius * .44f),
            radius * .025f, radius * .025f, p)

        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = h * .042f
        p.color = Color.rgb(237, 244, 245)
        canvas.drawText("HOME Menu", centerX, h * .555f, p)
    }

    private fun drawGamePreview(canvas: Canvas, w: Float, h: Float,
        preview: GamePreviewStore.Preview) {
        val picture = preview.bitmap?.takeUnless { it.isRecycled }
        val aspect = picture?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        val cardW = min(w * .79f, h * .47f * aspect)
        val cardH = cardW / aspect
        val left = (w - cardW) / 2f
        val top = (h * .355f) - cardH / 2f
        val box = RectF(left, top, left + cardW, top + cardH)
        val corner = min(w, h) * .022f

        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(RectF(box.left - 8f, box.top + 10f,
            box.right + 8f, box.bottom + 18f), corner * 1.3f, corner * 1.3f, p)
        p.color = Color.rgb(21, 27, 34)
        canvas.drawRoundRect(box, corner, corner, p)

        val inner = RectF(box.left + 4f, box.top + 4f,
            box.right - 4f, box.bottom - 4f)
        clippingPath.reset()
        clippingPath.addRoundRect(inner, corner * .85f, corner * .85f, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clippingPath)
        if (picture != null) {
            p.color = Color.WHITE
            val sourceAspect = picture.width.toFloat() / picture.height
            val destination = if (sourceAspect > inner.width() / inner.height()) {
                val scaledHeight = inner.width() / sourceAspect
                RectF(inner.left, inner.centerY() - scaledHeight / 2f,
                    inner.right, inner.centerY() + scaledHeight / 2f)
            } else {
                val scaledWidth = inner.height() * sourceAspect
                RectF(inner.centerX() - scaledWidth / 2f, inner.top,
                    inner.centerX() + scaledWidth / 2f, inner.bottom)
            }
            canvas.drawBitmap(picture, null, destination, p)
        } else {
            val pkg = preview.packageName
            if (pkg != iconFor || cachedIcon == null) {
                iconFor = pkg
                cachedIcon = try {
                    pkg?.let { context.packageManager.getApplicationIcon(it) }
                } catch (_: Exception) { null }
                    ?: context.getDrawable(android.R.drawable.sym_def_app_icon)
            }
            cachedIcon?.let { icon ->
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
    }
}
