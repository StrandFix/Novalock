package pl.iskri.pocketlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Drawn at device resolution: no blurry bitmap or unnecessary image decoding.
 * Minimal Dark is default; the original Cocoon Grid remains selectable.
 * No time-based animation or timers: zero rendering work during deep sleep.
 */
class NovaLockBackground @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastTheme: String? = null
    private var shaderHeight = -1
    private var smoothGradient: LinearGradient? = null
    private var gridGradient: LinearGradient? = null

    fun refreshTheme() {
        lastTheme = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val theme = Prefs.backgroundTheme(context)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        if (lastTheme != theme || shaderHeight != height) {
            smoothGradient = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(
                    Color.rgb(46, 47, 49),
                    Color.rgb(38, 39, 41),
                    Color.rgb(31, 32, 35)
                ), null, Shader.TileMode.CLAMP)
            gridGradient = LinearGradient(0f, 0f, 0f, h,
                Color.rgb(36, 42, 51), Color.rgb(10, 14, 20), Shader.TileMode.CLAMP)
            lastTheme = theme
            shaderHeight = height
        }

        paint.style = Paint.Style.FILL
        paint.shader = if (theme == Prefs.BG_COCOON) gridGradient else smoothGradient
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        if (theme != Prefs.BG_COCOON) return
        // Original low-contrast Cocoon tiled design, kept entirely unchanged.
        val side = (width / 7.4f).coerceAtLeast(28f)
        val gap = side * .14f
        val step = side + gap
        var y = -side * .36f
        var row = 0
        while (y < height) {
            var x = if (row % 2 == 0) -side * .35f else -side * .77f
            while (x < width) {
                paint.color = if ((row + (x / step).toInt()) % 3 == 0)
                    Color.argb(48, 78, 88, 103) else Color.argb(34, 69, 80, 94)
                canvas.drawRoundRect(RectF(x, y, x + side, y + side),
                    side * .17f, side * .17f, paint)
                x += step
            }
            y += step
            row++
        }
    }
}
