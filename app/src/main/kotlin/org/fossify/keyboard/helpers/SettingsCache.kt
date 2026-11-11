package org.fossify.keyboard.helpers

import android.content.Context
import android.content.SharedPreferences
import org.fossify.keyboard.extensions.config
import org.fossify.keyboard.extensions.safeStorageContext

/**
 * Ultra-low-latency, zero-allocation in-memory cache for keyboard settings
 * evaluated in the real-time touch, rendering, and feedback pipelines.
 *
 * Implements [SharedPreferences.OnSharedPreferenceChangeListener] to automatically
 * keep volatile fields in sync with disk storage. In the hot touch path (e.g., onDown,
 * hit testing, long-press timer setup), reading these volatile properties incurs
 * zero disk I/O, zero synchronization blocking, and zero allocations.
 */
class SettingsCache private constructor(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        @Volatile
        private var INSTANCE: SettingsCache? = null

        fun getInstance(context: Context): SettingsCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsCache(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.safeStorageContext.config.sharedPrefs

    // Volatile primitive fields for lock-free, zero-allocation reads in the touch path
    @Volatile
    var vibrateOnKeypress: Boolean = true
        private set

    @Volatile
    var hapticStrength: Int = DEFAULT_HAPTIC_STRENGTH
        private set

    @Volatile
    var soundOnKeypress: Int = SOUND_SYSTEM
        private set

    @Volatile
    var soundVolume: Float = DEFAULT_SOUND_VOLUME / 100f
        private set

    @Volatile
    var showPopupOnKeypress: Boolean = true
        private set

    @Volatile
    var longPressDelayMs: Long = DEFAULT_LONG_PRESS_DELAY_MS
        private set

    @Volatile
    var personaliseTouch: Boolean = true
        private set

    @Volatile
    var showTouchDebugOverlay: Boolean = false
        private set

    @Volatile
    var keyboardHeightPreset: Int = 3
        private set

    init {
        // Register strong reference listener to avoid being garbage-collected
        prefs.registerOnSharedPreferenceChangeListener(this)
        reloadAll()
    }

    /**
     * Synchronously refreshes all cached values from SharedPreferences.
     */
    fun reloadAll() {
        vibrateOnKeypress = prefs.getBoolean(VIBRATE_ON_KEYPRESS, true)
        hapticStrength = prefs.getInt(HAPTIC_STRENGTH, DEFAULT_HAPTIC_STRENGTH).coerceIn(1, 100)
        soundOnKeypress = prefs.getInt(SOUND_ON_KEYPRESS, SOUND_SYSTEM)
        val volInt = prefs.getInt(SOUND_VOLUME, DEFAULT_SOUND_VOLUME).coerceIn(0, 100)
        soundVolume = volInt / 100f
        showPopupOnKeypress = prefs.getBoolean(SHOW_POPUP_ON_KEYPRESS, true)
        longPressDelayMs = prefs.getLong(LONG_PRESS_DELAY_MS, DEFAULT_LONG_PRESS_DELAY_MS).coerceIn(100L, 700L)
        personaliseTouch = prefs.getBoolean(PERSONALISE_TOUCH, true)
        showTouchDebugOverlay = prefs.getBoolean(SHOW_TOUCH_DEBUG_OVERLAY, false)
        keyboardHeightPreset = prefs.getInt(KEYBOARD_HEIGHT_PRESET, 3)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            VIBRATE_ON_KEYPRESS -> {
                vibrateOnKeypress = prefs.getBoolean(VIBRATE_ON_KEYPRESS, true)
            }
            HAPTIC_STRENGTH -> {
                hapticStrength = prefs.getInt(HAPTIC_STRENGTH, DEFAULT_HAPTIC_STRENGTH).coerceIn(1, 100)
            }
            SOUND_ON_KEYPRESS -> {
                soundOnKeypress = prefs.getInt(SOUND_ON_KEYPRESS, SOUND_SYSTEM)
            }
            SOUND_VOLUME -> {
                val volInt = prefs.getInt(SOUND_VOLUME, DEFAULT_SOUND_VOLUME).coerceIn(0, 100)
                soundVolume = volInt / 100f
            }
            SHOW_POPUP_ON_KEYPRESS -> {
                showPopupOnKeypress = prefs.getBoolean(SHOW_POPUP_ON_KEYPRESS, true)
            }
            LONG_PRESS_DELAY_MS -> {
                longPressDelayMs = prefs.getLong(LONG_PRESS_DELAY_MS, DEFAULT_LONG_PRESS_DELAY_MS).coerceIn(100L, 700L)
            }
            PERSONALISE_TOUCH -> {
                personaliseTouch = prefs.getBoolean(PERSONALISE_TOUCH, true)
            }
            SHOW_TOUCH_DEBUG_OVERLAY -> {
                showTouchDebugOverlay = prefs.getBoolean(SHOW_TOUCH_DEBUG_OVERLAY, false)
            }
            KEYBOARD_HEIGHT_PRESET -> {
                keyboardHeightPreset = prefs.getInt(KEYBOARD_HEIGHT_PRESET, 3)
            }
        }
    }
}
