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

/** Low-color Cocoon-like rounded tiles, scaled to the Nova screen. */
class NovaLockBackground @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            Color.rgb(36, 42, 51), Color.rgb(10, 14, 20), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
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
