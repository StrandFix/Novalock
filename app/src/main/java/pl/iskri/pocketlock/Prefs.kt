package pl.iskri.pocketlock

import android.content.Context

object Prefs {

    private const val FILE = "pocketlock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_KEY = "last_key"
    private const val KEY_ANIMATION_TYPE = "animation_type"
    private const val KEY_ANIMATION_DURATION = "animation_duration"
    private const val DEFAULT_ANIMATION_DURATION = 350
    private const val KEY_BARS_VISIBLE = "bars_visible"

    const val ANIMATION_SLIDE_DOWN = "slide_down"
    const val ANIMATION_SLIDE_UP = "slide_up"
    const val ANIMATION_FADE = "fade"
    const val ANIMATION_ZOOM_OUT = "zoom_out"
    const val ANIMATION_ZOOM_IN = "zoom_in"
    const val ANIMATION_FADE_SLIDE = "fade_slide"
    private const val KEY_SOUND = "sound_status"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_MEDIA_MUTED = "media_muted"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    private const val KEY_SCREEN_OFF_SECONDS = "screen_off_seconds"
    private const val DEFAULT_SCREEN_OFF_SECONDS = 10
    private const val KEY_REMEMBER_PRESSES = "remember_presses"
    private const val KEY_TOUCH_ENABLED = "touch_enabled"
    private const val KEY_TRIGGERS_ENABLED = "triggers_enabled"
    private const val KEY_BUTTONS_KNOWN = "buttons_known"
    private const val KEY_BUTTONS_REMOVED = "buttons_removed"
    private const val KEY_PRESS_COUNT = "press_count"

    private const val KEY_BG_ENABLED = "bg_enabled"
    private const val KEY_BG_SCALE = "bg_scale"
    private const val KEY_BG_OFFSET_X = "bg_offset_x"
    private const val KEY_BG_OFFSET_Y = "bg_offset_y"
    private const val KEY_DOT_SCALE = "dot_scale"
    private const val KEY_DOT_SPACING = "dot_spacing"
    private const val KEY_DOT_CENTER_X = "dot_center_x"
    private const val KEY_DOT_CENTER_Y = "dot_center_y"
    private const val KEY_DOT_ACTIVE_COLOR = "dot_active_color"
    private const val KEY_DOT_INACTIVE_COLOR = "dot_inactive_color"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isSoundEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun isVibrationEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_VIBRATION_ENABLED, true)

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }

    fun isNotificationEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_NOTIFICATION_ENABLED, true)

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
    }

    fun screenOffSeconds(context: Context): Int =
        sp(context).getInt(KEY_SCREEN_OFF_SECONDS, DEFAULT_SCREEN_OFF_SECONDS)

    fun setScreenOffSeconds(context: Context, seconds: Int) {
        sp(context).edit().putInt(KEY_SCREEN_OFF_SECONDS, seconds).apply()
    }

    /** Whether the already-pressed dots are kept when the screen turns off again. */
    fun rememberPresses(context: Context): Boolean =
        sp(context).getBoolean(KEY_REMEMBER_PRESSES, false)

    fun setRememberPresses(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_REMEMBER_PRESSES, enabled).apply()
    }

    /** Whether tapping the screen counts as an unlock press. */
    fun isTouchEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_TOUCH_ENABLED, true)

    fun setTouchEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_TOUCH_ENABLED, enabled).apply()
    }

    /** Whether the analog L2/R2 triggers count as unlock presses. */
    fun isTriggersEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_TRIGGERS_ENABLED, true)

    fun setTriggersEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_TRIGGERS_ENABLED, enabled).apply()
    }

    /** Every button/key code ever seen on the lock screen; used to populate the buttons list. */
    fun capturedButtons(context: Context): Set<Int> =
        sp(context).getStringSet(KEY_BUTTONS_KNOWN, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    fun captureButton(context: Context, keyCode: Int) {
        val sp = sp(context)
        val current = sp.getStringSet(KEY_BUTTONS_KNOWN, emptySet()) ?: emptySet()
        val value = keyCode.toString()
        if (value in current) return
        val next = HashSet(current)
        next.add(value)
        sp.edit().putStringSet(KEY_BUTTONS_KNOWN, next).apply()
    }

    /**
     * Buttons the user removed from the unlock list. A key code that is not here is allowed, so
     * every button the app detects works by default and only explicit removals restrict it.
     */
    fun removedButtons(context: Context): Set<Int> =
        sp(context).getStringSet(KEY_BUTTONS_REMOVED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    fun removeButton(context: Context, keyCode: Int) {
        val sp = sp(context)
        val next = HashSet(sp.getStringSet(KEY_BUTTONS_REMOVED, emptySet()) ?: emptySet())
        if (!next.add(keyCode.toString())) return
        sp.edit().putStringSet(KEY_BUTTONS_REMOVED, next).apply()
    }

    fun restoreButton(context: Context, keyCode: Int) {
        val sp = sp(context)
        val current = sp.getStringSet(KEY_BUTTONS_REMOVED, emptySet()) ?: emptySet()
        val value = keyCode.toString()
        if (value !in current) return
        val next = HashSet(current)
        next.remove(value)
        sp.edit().putStringSet(KEY_BUTTONS_REMOVED, next).apply()
    }

    /** Removes every detected button from the unlock list. */
    fun removeAllButtons(context: Context) {
        val removed = capturedButtons(context).map { it.toString() }.toSet()
        sp(context).edit().putStringSet(KEY_BUTTONS_REMOVED, removed).apply()
    }

    /** A detected button that is not removed; the unlock fallback uses the lowest one. */
    fun fallbackButton(context: Context): Int? =
        (capturedButtons(context) - removedButtons(context)).minOrNull()
            ?: capturedButtons(context).minOrNull()

    /**
     * Whether at least one unlock method is currently enabled. Used as a safety net: if the
     * user turned everything off (touch, triggers and every detected button), taps and one
     * detected button keep working so the screen can never be locked for good.
     */
    fun hasUnlockMethod(context: Context): Boolean =
        isTouchEnabled(context) ||
            isTriggersEnabled(context) ||
            (capturedButtons(context) - removedButtons(context)).isNotEmpty()

    fun isButtonAllowed(context: Context, keyCode: Int): Boolean {
        if (keyCode !in removedButtons(context)) return true
        return !hasUnlockMethod(context) && keyCode == fallbackButton(context)
    }

    fun pressCount(context: Context): Int = sp(context).getInt(KEY_PRESS_COUNT, 0)

    fun setPressCount(context: Context, count: Int) {
        sp(context).edit().putInt(KEY_PRESS_COUNT, count).apply()
    }

    /** Whether the system bars were visible before the screen was locked; null if unknown. */
    fun barsVisible(context: Context): Boolean? =
        if (sp(context).contains(KEY_BARS_VISIBLE)) {
            sp(context).getBoolean(KEY_BARS_VISIBLE, false)
        } else {
            null
        }

    fun setBarsVisible(context: Context, visible: Boolean) {
        sp(context).edit().putBoolean(KEY_BARS_VISIBLE, visible).apply()
    }

    fun animationType(context: Context): String =
        sp(context).getString(KEY_ANIMATION_TYPE, ANIMATION_SLIDE_DOWN)
            ?: ANIMATION_SLIDE_DOWN

    fun setAnimationType(context: Context, value: String) {
        sp(context).edit().putString(KEY_ANIMATION_TYPE, value).apply()
    }

    fun animationDuration(context: Context): Int =
        sp(context).getInt(KEY_ANIMATION_DURATION, DEFAULT_ANIMATION_DURATION)

    fun setAnimationDuration(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_ANIMATION_DURATION, value).apply()
    }

    fun lastKey(context: Context): String =
        sp(context).getString(KEY_LAST_KEY, "-") ?: "-"

    fun setLastKey(context: Context, value: String) {
        sp(context).edit().putString(KEY_LAST_KEY, value).apply()
    }

    fun soundStatus(context: Context): String =
        sp(context).getString(KEY_SOUND, "-") ?: "-"

    fun setSoundStatus(context: Context, value: String) {
        sp(context).edit().putString(KEY_SOUND, value).apply()
    }

    /** Whether the media stream was muted by the lock and still has to be restored. */
    fun mediaMuted(context: Context): Boolean =
        sp(context).getBoolean(KEY_MEDIA_MUTED, false)

    fun setMediaMuted(context: Context, muted: Boolean) {
        sp(context).edit().putBoolean(KEY_MEDIA_MUTED, muted).apply()
    }

    fun isBackgroundEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_BG_ENABLED, false)

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_BG_ENABLED, enabled).apply()
    }

    fun backgroundScale(context: Context): Float = sp(context).getFloat(KEY_BG_SCALE, 1f)

    fun setBackgroundScale(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_BG_SCALE, value).apply()
    }

    fun backgroundOffsetX(context: Context): Float = sp(context).getFloat(KEY_BG_OFFSET_X, 0f)

    fun backgroundOffsetY(context: Context): Float = sp(context).getFloat(KEY_BG_OFFSET_Y, 0f)

    fun setBackgroundOffset(context: Context, x: Float, y: Float) {
        sp(context).edit().putFloat(KEY_BG_OFFSET_X, x).putFloat(KEY_BG_OFFSET_Y, y).apply()
    }

    fun dotScale(context: Context): Float = sp(context).getFloat(KEY_DOT_SCALE, 1.2f)

    fun setDotScale(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_DOT_SCALE, value).apply()
    }

    fun dotSpacing(context: Context): Float = sp(context).getFloat(KEY_DOT_SPACING, 1f)

    fun setDotSpacing(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_DOT_SPACING, value).apply()
    }

    fun dotCenterX(context: Context): Float = sp(context).getFloat(KEY_DOT_CENTER_X, 0.5f)

    fun dotCenterY(context: Context): Float = sp(context).getFloat(KEY_DOT_CENTER_Y, 0.5f)

    fun setDotCenter(context: Context, x: Float, y: Float) {
        sp(context).edit().putFloat(KEY_DOT_CENTER_X, x).putFloat(KEY_DOT_CENTER_Y, y).apply()
    }

    fun dotActiveColor(context: Context): Int =
        sp(context).getInt(KEY_DOT_ACTIVE_COLOR, 0xFFFFFFFF.toInt())

    fun setDotActiveColor(context: Context, color: Int) {
        sp(context).edit().putInt(KEY_DOT_ACTIVE_COLOR, color).apply()
    }

    fun dotInactiveColor(context: Context): Int =
        sp(context).getInt(KEY_DOT_INACTIVE_COLOR, 0x26FFFFFF)

    fun setDotInactiveColor(context: Context, color: Int) {
        sp(context).edit().putInt(KEY_DOT_INACTIVE_COLOR, color).apply()
    }

    fun resetAppearance(context: Context) {
        sp(context).edit()
            .remove(KEY_BG_SCALE)
            .remove(KEY_BG_OFFSET_X)
            .remove(KEY_BG_OFFSET_Y)
            .remove(KEY_DOT_SCALE)
            .remove(KEY_DOT_SPACING)
            .remove(KEY_DOT_CENTER_X)
            .remove(KEY_DOT_CENTER_Y)
            .remove(KEY_DOT_ACTIVE_COLOR)
            .remove(KEY_DOT_INACTIVE_COLOR)
            .apply()
    }

    fun resetBackgroundTransform(context: Context) {
        sp(context).edit()
            .remove(KEY_BG_SCALE)
            .remove(KEY_BG_OFFSET_X)
            .remove(KEY_BG_OFFSET_Y)
            .apply()
    }

    fun resetDotTransform(context: Context) {
        sp(context).edit()
            .remove(KEY_DOT_SCALE)
            .remove(KEY_DOT_SPACING)
            .remove(KEY_DOT_CENTER_X)
            .remove(KEY_DOT_CENTER_Y)
            .apply()
    }
}
