package pl.iskri.pocketlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Custom lock screen top bar: the current time on the left and the battery level on the right.
 * Both are live (the clock updates on the minute, the battery on every battery broadcast) and
 * the whole bar can be recoloured / made transparent from the appearance settings.
 */
class LockTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val clock: TextView
    private val battery: TextView
    private val batteryGroup: LinearLayout
    private val batteryIcon: ImageView
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var attached = false
    private var batteryLevel = -1
    private var batteryCharging = false

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            if (attached) handler.postDelayed(this, MINUTE_MS)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clock = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        battery = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        batteryIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_battery)
            contentDescription = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        batteryGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(
                batteryIcon,
                LayoutParams(dp(23f), dp(23f)).apply { marginEnd = dp(2f) }
            )
            addView(battery)
        }
        addView(clock, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(
            batteryGroup,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        registerBattery()
        startClock()
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler.removeCallbacks(clockTick)
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
        super.onDetachedFromWindow()
    }

    fun applyAppearance() {
        val enabled = Prefs.isTopBarEnabled(context)
        visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        val scale = Prefs.topBarScale(context).coerceIn(0.5f, 2f)
        setBackgroundColor(Prefs.topBarBackgroundColor(context))
        val textColor = Prefs.topBarTextColor(context)
        val showClock = Prefs.isTopBarClockEnabled(context)
        clock.visibility = if (showClock) View.VISIBLE else View.GONE
        updateChildLayout(showClock)
        clock.setTextColor(textColor)
        battery.setTextColor(textColor)
        batteryIcon.imageTintList = ColorStateList.valueOf(textColor)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_SP * scale)
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, BATTERY_SP * scale)
        val iconScale = ICON_SCALE_BASE + (scale - 1f).coerceAtLeast(0f) * ICON_SCALE_GROWTH
        val iconSize = sp(BATTERY_SP * scale * iconScale)
        batteryIcon.layoutParams = LayoutParams(iconSize, iconSize).apply {
            marginEnd = dp(2f * scale)
        }
        val padH = dp(BASE_PADDING_H * scale)
        val padV = dp(BASE_PADDING_V * scale)
        setPadding(padH, padV, padH, padV)
        applyMargins()
        updateClock()
        readBatteryFromManager()
        updateBattery()
    }

    private fun startClock() {
        handler.removeCallbacks(clockTick)
        updateClock()
        // Stay in sync with the wall clock: fire right after the next full minute.
        val untilNextMinute = MINUTE_MS - System.currentTimeMillis() % MINUTE_MS
        handler.postDelayed(clockTick, untilNextMinute + 50L)
    }

    private fun updateClock() {
        val formatter = when (Prefs.topBarClockFormat(context)) {
            Prefs.CLOCK_FORMAT_24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
            Prefs.CLOCK_FORMAT_12 -> SimpleDateFormat("h:mm a", Locale.getDefault())
            else -> DateFormat.getTimeFormat(context)
        }
        clock.text = formatter.format(Date())
    }

    private fun updateChildLayout(showClock: Boolean) {
        clock.layoutParams = LinearLayout.LayoutParams(
            0,
            LayoutParams.WRAP_CONTENT,
            if (showClock) 1f else 0f
        )
        batteryGroup.layoutParams = LinearLayout.LayoutParams(
            if (showClock) LayoutParams.WRAP_CONTENT else 0,
            LayoutParams.WRAP_CONTENT,
            if (showClock) 0f else 1f
        )
    }

    private fun applyMargins() {
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        val top = dp(Prefs.topBarMarginTop(context))
        val right = dp(Prefs.topBarMarginRight(context))
        val left = dp(Prefs.topBarMarginLeft(context))
        if (params.topMargin == top && params.rightMargin == right && params.leftMargin == left) {
            return
        }
        params.topMargin = top
        params.rightMargin = right
        params.leftMargin = left
        layoutParams = params
    }

    private fun registerBattery() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                readBattery(intent)
            }
        }
        try {
            val sticky = context.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            receiver = r
            sticky?.let { readBattery(it) }
            readBatteryFromManager()
        } catch (_: Exception) {
            receiver = null
        }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            batteryLevel = (level * 100 / scale).coerceIn(0, 100)
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
        updateBattery()
    }

    private fun readBatteryFromManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) batteryLevel = level
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val status = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                if (status != Int.MIN_VALUE) {
                    batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                }
            }
        } catch (_: Exception) {
        }
        updateBattery()
    }

    private fun updateBattery() {
        battery.text = if (batteryLevel in 0..100) "$batteryLevel%" else ""
        batteryIcon.setImageResource(
            if (batteryCharging) R.drawable.ic_charging else R.drawable.ic_battery
        )
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()

    private fun sp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        ).roundToInt()

    private companion object {
        const val MINUTE_MS = 60_000L
        const val CLOCK_SP = 20f
        const val BATTERY_SP = 18f
        const val ICON_SCALE_BASE = 0.9f
        const val ICON_SCALE_GROWTH = 0.7f
        const val BASE_PADDING_H = 20f
        const val BASE_PADDING_V = 10f
    }
}
