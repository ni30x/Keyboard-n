package org.fossify.keyboard.helpers

import android.content.Context
import android.content.SharedPreferences
import org.fossify.commons.helpers.BaseConfig
import org.fossify.keyboard.extensions.isDeviceLocked
import org.fossify.keyboard.extensions.safeStorageContext
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context.safeStorageContext)
    }

    val sharedPrefs: SharedPreferences
        get() = prefs

    var vibrateOnKeypress: Boolean
        get() = prefs.getBoolean(VIBRATE_ON_KEYPRESS, true)
        set(vibrateOnKeypress) = prefs.edit().putBoolean(VIBRATE_ON_KEYPRESS, vibrateOnKeypress).apply()

    var hapticStrength: Int
        get() = prefs.getInt(HAPTIC_STRENGTH, DEFAULT_HAPTIC_STRENGTH)
        set(hapticStrength) = prefs.edit().putInt(HAPTIC_STRENGTH, hapticStrength).apply()

    var soundOnKeypress: Int
        get() = prefs.getInt(SOUND_ON_KEYPRESS, SOUND_SYSTEM)
        set(soundOnKeypress) = prefs.edit().putInt(SOUND_ON_KEYPRESS, soundOnKeypress).apply()

    var soundVolume: Int
        get() = prefs.getInt(SOUND_VOLUME, DEFAULT_SOUND_VOLUME)
        set(soundVolume) = prefs.edit().putInt(SOUND_VOLUME, soundVolume).apply()

    var showPopupOnKeypress: Boolean
        get() = prefs.getBoolean(SHOW_POPUP_ON_KEYPRESS, true)
        set(showPopupOnKeypress) = prefs.edit().putBoolean(SHOW_POPUP_ON_KEYPRESS, showPopupOnKeypress).apply()

    var longPressDelayMs: Long
        get() = prefs.getLong(LONG_PRESS_DELAY_MS, DEFAULT_LONG_PRESS_DELAY_MS)
        set(delay) = prefs.edit().putLong(LONG_PRESS_DELAY_MS, delay).apply()

    var personaliseTouch: Boolean
        get() = prefs.getBoolean(PERSONALISE_TOUCH, true)
        set(personalise) = prefs.edit().putBoolean(PERSONALISE_TOUCH, personalise).apply()

    var showTouchDebugOverlay: Boolean
        get() = prefs.getBoolean(SHOW_TOUCH_DEBUG_OVERLAY, false)
        set(show) = prefs.edit().putBoolean(SHOW_TOUCH_DEBUG_OVERLAY, show).apply()

    var autoSyncMaterialYou: Boolean
        get() = prefs.getBoolean(AUTO_SYNC_MATERIAL_YOU, true)
        set(autoSync) = prefs.edit().putBoolean(AUTO_SYNC_MATERIAL_YOU, autoSync).apply()

    var enableSentencesCapitalization: Boolean
        get() = prefs.getBoolean(SENTENCES_CAPITALIZATION, true)
        set(enableCapitalization) = prefs.edit().putBoolean(SENTENCES_CAPITALIZATION, enableCapitalization).apply()

    var showEmojiKey: Boolean
        get() = prefs.getBoolean(SHOW_EMOJI_KEY, true)
        set(showEmojiKey) = prefs.edit().putBoolean(SHOW_EMOJI_KEY, showEmojiKey).apply()

    var showLanguageSwitchKey: Boolean
        get() = prefs.getBoolean(SHOW_LANGUAGE_SWITCH_KEY, false)
        set(showLanguageSwitchKey) = prefs.edit().putBoolean(SHOW_LANGUAGE_SWITCH_KEY, showLanguageSwitchKey).apply()

    var showKeyBorders: Boolean
        get() = prefs.getBoolean(SHOW_KEY_BORDERS, true)
        set(showKeyBorders) = prefs.edit().putBoolean(SHOW_KEY_BORDERS, showKeyBorders).apply()

    var keyboardHeightPreset: Int
        get() = prefs.getInt(KEYBOARD_HEIGHT_PRESET, 3)
        set(preset) = prefs.edit().putInt(KEYBOARD_HEIGHT_PRESET, preset).apply()

    var oneHandedMode: Int
        get() = prefs.getInt(KEYBOARD_ONE_HANDED_MODE, 0)
        set(mode) = prefs.edit().putInt(KEYBOARD_ONE_HANDED_MODE, mode).apply()

    var longPressSymbols: Boolean
        get() = prefs.getBoolean(LONG_PRESS_SYMBOLS, true)
        set(enable) = prefs.edit().putBoolean(LONG_PRESS_SYMBOLS, enable).apply()

    var lastExportedClipsFolder: String
        get() = prefs.getString(LAST_EXPORTED_CLIPS_FOLDER, "")!!
        set(lastExportedClipsFolder) = prefs.edit().putString(LAST_EXPORTED_CLIPS_FOLDER, lastExportedClipsFolder).apply()

    var keyboardLanguage: Int
        get() = prefs.getInt(KEYBOARD_LANGUAGE, getDefaultLanguage())
        set(keyboardLanguage) = prefs.edit().putInt(KEYBOARD_LANGUAGE, keyboardLanguage).apply()

    var keyboardHeightPercentage: Int
        get() = prefs.getInt(HEIGHT_PERCENTAGE, 100)
        set(keyboardHeightMultiplier) = prefs.edit().putInt(HEIGHT_PERCENTAGE, keyboardHeightMultiplier).apply()

    var showClipboardContent: Boolean
        get() = prefs.getBoolean(SHOW_CLIPBOARD_CONTENT, true)
        set(showClipboardContent) = prefs.edit().putBoolean(SHOW_CLIPBOARD_CONTENT, showClipboardContent).apply()

    var showNumbersRow: Boolean
        get() = if (context.isDeviceLocked) {
            true
        } else {
            prefs.getBoolean(SHOW_NUMBERS_ROW, false)
        }
        set(showNumbersRow) = prefs.edit().putBoolean(SHOW_NUMBERS_ROW, showNumbersRow).apply()

    var voiceInputMethod: String
        get() = prefs.getString(VOICE_INPUT_METHOD, "")!!
        set(voiceInputMethod) = prefs.edit().putString(VOICE_INPUT_METHOD, voiceInputMethod).apply()

    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean(DOUBLE_SPACE_PERIOD, true)
        set(doubleSpacePeriod) = prefs.edit().putBoolean(DOUBLE_SPACE_PERIOD, doubleSpacePeriod).apply()

    var recordInputHistory: Boolean
        get() = prefs.getBoolean(RECORD_INPUT_HISTORY, true)
        set(record) = prefs.edit().putBoolean(RECORD_INPUT_HISTORY, record).apply()

    var blockedHistoryPackages: MutableSet<String>
        get() = prefs.getStringSet(BLOCKED_HISTORY_PACKAGES, HashSet())?.toMutableSet() ?: HashSet()
        set(packages) = prefs.edit().putStringSet(BLOCKED_HISTORY_PACKAGES, packages).apply()

    var historyRetentionDays: Int
        get() = prefs.getInt(HISTORY_RETENTION_DAYS, 0)
        set(days) = prefs.edit().putInt(HISTORY_RETENTION_DAYS, days).apply()

    var historyLockEnabled: Boolean
        get() = prefs.getBoolean(HISTORY_LOCK_ENABLED, false)
        set(enabled) = prefs.edit().putBoolean(HISTORY_LOCK_ENABLED, enabled).apply()

    var selectedLanguages: MutableSet<Int>
        get() {
            val defaultLanguage = getDefaultLanguage().toString()
            val stringSet = prefs.getStringSet(SELECTED_LANGUAGES, hashSetOf(defaultLanguage))!!
            return stringSet.map { it.toInt() }.toMutableSet()
        }
        set(selectedLanguages) {
            val stringSet = selectedLanguages.map { it.toString() }.toSet()
            prefs.edit().putStringSet(SELECTED_LANGUAGES, stringSet).apply()
        }

    fun getDefaultLanguage(): Int {
        val conf = context.resources.configuration
        return if (conf.locale.toString().lowercase(Locale.getDefault()).startsWith("ru_")) {
            LANGUAGE_RUSSIAN
        } else {
            LANGUAGE_ENGLISH_QWERTY
        }
    }

    var recentlyUsedEmojis: List<String>
        get() = prefs.getString(RECENTLY_USED_EMOJIS, "\uD83D\uDC30")!!.split("|").filter { it.isNotEmpty() }
        set(recentlyUsedEmojis) = prefs.edit().putString(
            RECENTLY_USED_EMOJIS, recentlyUsedEmojis.joinToString("|")
        ).apply()

    fun addRecentEmoji(emoji: String) {
        val recentEmojis = recentlyUsedEmojis.toMutableList()
        recentEmojis.remove(emoji)
        recentEmojis.add(0, emoji)
        recentlyUsedEmojis = recentEmojis.take(RECENT_EMOJIS_LIMIT)
    }
}
