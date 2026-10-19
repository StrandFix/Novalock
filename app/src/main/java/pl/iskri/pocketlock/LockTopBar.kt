package pl.iskri.pocketlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 * Nova status strip — perfectly centered clock + right-aligned battery.
 * Existing appearance settings (visibility, clock format, margins, opacity,
 * text color and size) remain active. A green lightning battery appears
 * while charging. No timer/receiver remains active while asleep.
 */
class LockTopBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val clock = TextView(context)
    private val battery = TextView(context)
    private val batteryIcon = ImageView(context)
    private val batteryGroup = LinearLayout(context)
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var displayAwake = false
    private var batteryLevel = -1
    private var charging = false
    private var textColor = Color.WHITE
    private var scale = .9f

    private val clockTick = object : Runnable {
        override fun run() {
            if (!displayAwake || !isAttachedToWindow) return
            updateClock()
            val delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L
            handler.postDelayed(this, delay)
        }
    }

    init {
        setWillNotDraw(false)
        minimumHeight = dp(46f)
        clock.apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL)
            setSingleLine(true)
            setTextColor(Color.WHITE)
        }
        battery.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
            setSingleLine(true)
            setTextColor(Color.WHITE)
        }
        batteryIcon.apply {
            setImageResource(R.drawable.ic_battery)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        }
        batteryGroup.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(batteryIcon, LinearLayout.LayoutParams(dp(13f), dp(21f)).apply {
                marginEnd = dp(6f)
            })
            addView(battery, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(batteryGroup, LayoutParams(LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyAppearance()
    }

    override fun onDetachedFromWindow() {
        setDisplayAwake(false)
        super.onDetachedFromWindow()
    }

    /** Explicitly controlled by the lock screen SCREEN_ON/OFF lifecycle. */
    fun setDisplayAwake(awake: Boolean) {
        val shouldWake = awake && isAttachedToWindow && visibility == View.VISIBLE
        if (displayAwake == shouldWake) return
        displayAwake = shouldWake
        handler.removeCallbacks(clockTick)
        if (shouldWake) {
            updateClock()
            registerBatteryReceiver()
            clockTick.run()
        } else {
            receiver?.let {
                try { context.unregisterReceiver(it) } catch (_: Exception) {}
            }
            receiver = null
        }
    }

    fun applyAppearance() {
        visibility = if (Prefs.isTopBarEnabled(context)) View.VISIBLE else View.GONE
        if (visibility != View.VISIBLE) {
            setDisplayAwake(false)
            return
        }
        scale = Prefs.topBarScale(context).coerceIn(.5f, 1.5f)
        textColor = Prefs.topBarTextColor(context)
        setBackgroundColor(Prefs.topBarBackgroundColor(context))
        clock.visibility = if (Prefs.isTopBarClockEnabled(context)) View.VISIBLE else View.INVISIBLE
        clock.setTextColor(textColor)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scale)
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        setPadding(
            dp(Prefs.topBarMarginLeft(context)),
            dp(Prefs.topBarMarginTop(context)),
            dp(Prefs.topBarMarginRight(context)),
            dp(4f)
        )
        val batteryLp = batteryGroup.layoutParams as LayoutParams
        batteryLp.gravity = Gravity.TOP or Gravity.END
        batteryLp.topMargin = dp(3f)
        batteryGroup.layoutParams = batteryLp
        updateClock()
        readBatteryFromManager()
        updateBattery()
    }

    private fun updateClock() {
        val formatter = when (Prefs.topBarClockFormat(context)) {
            Prefs.CLOCK_FORMAT_24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
            Prefs.CLOCK_FORMAT_12 -> SimpleDateFormat("h:mm a", Locale.getDefault())
            else -> DateFormat.getTimeFormat(context)
        }
        clock.text = formatter.format(Date())
    }

    private fun registerBatteryReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                readBattery(intent)
            }
        }
        try {
            val sticky = context.registerReceiver(r,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            receiver = r
            sticky?.let { readBattery(it) }
        } catch (_: Exception) {
            receiver = null
        }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val max = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && max > 0) batteryLevel = (level * 100 / max).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        charging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0)
        updateBattery()
    }

    private fun readBatteryFromManager() {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) batteryLevel = level
        } catch (_: Exception) {}
        // Battery charging is obtained reliably from ACTION_BATTERY_CHANGED.
        val sticky = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { null }
        sticky?.let { readBattery(it) }
    }

    private fun updateBattery() {
        val tint = if (charging) Color.rgb(87, 226, 154) else textColor
        battery.text = if (batteryLevel in 0..100) "$batteryLevel%" else "—"
        battery.setTextColor(tint)
        batteryIcon.setImageResource(if (charging)
            R.drawable.ic_charging else R.drawable.ic_battery)
        batteryIcon.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            value, resources.displayMetrics).roundToInt()
}
