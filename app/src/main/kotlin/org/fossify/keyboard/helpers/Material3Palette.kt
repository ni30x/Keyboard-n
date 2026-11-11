package org.fossify.keyboard.helpers

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.darkenColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.lightenColor
import org.fossify.keyboard.extensions.config
import org.fossify.keyboard.extensions.safeStorageContext

data class Material3Palette(
    @ColorInt val backgroundColor: Int,
    @ColorInt val letterKeyColor: Int,
    @ColorInt val letterKeyColorPressed: Int,
    @ColorInt val functionalKeyColor: Int,
    @ColorInt val functionalKeyColorPressed: Int,
    @ColorInt val enterKeyColor: Int,
    @ColorInt val enterKeyColorPressed: Int,
    @ColorInt val enterTextColor: Int,
    @ColorInt val textColor: Int,
    @ColorInt val secondaryTextColor: Int,
    @ColorInt val accentColor: Int,
    @ColorInt val strokeColor: Int,
    val isDark: Boolean
) {
    companion object {
        fun resolve(context: Context): Material3Palette {
            val config = context.config
            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val autoSync = config.autoSyncMaterialYou

            if (autoSync && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+) Material You Dynamic Colors from Monet palette
                val res = context.resources
                val theme = context.theme

                val bg = if (isDark) {
                    res.getColor(android.R.color.system_neutral1_900, theme)
                } else {
                    res.getColor(android.R.color.system_neutral1_50, theme)
                }

                val letterKey = if (isDark) {
                    res.getColor(android.R.color.system_neutral1_800, theme)
                } else {
                    Color.WHITE
                }

                val letterKeyPressed = if (isDark) {
                    res.getColor(android.R.color.system_neutral1_700, theme)
                } else {
                    res.getColor(android.R.color.system_neutral1_100, theme)
                }

                val funcKey = if (isDark) {
                    res.getColor(android.R.color.system_neutral2_700, theme)
                } else {
                    res.getColor(android.R.color.system_neutral2_100, theme)
                }

                val funcKeyPressed = if (isDark) {
                    res.getColor(android.R.color.system_neutral2_600, theme)
                } else {
                    res.getColor(android.R.color.system_neutral2_200, theme)
                }

                val enterKey = if (isDark) {
                    res.getColor(android.R.color.system_accent1_200, theme)
                } else {
                    res.getColor(android.R.color.system_accent1_600, theme)
                }

                val enterKeyPressed = if (isDark) {
                    res.getColor(android.R.color.system_accent1_300, theme)
                } else {
                    res.getColor(android.R.color.system_accent1_700, theme)
                }

                val enterText = if (isDark) {
                    res.getColor(android.R.color.system_accent1_900, theme)
                } else {
                    Color.WHITE
                }

                val text = if (isDark) {
                    res.getColor(android.R.color.system_neutral1_50, theme)
                } else {
                    res.getColor(android.R.color.system_neutral1_900, theme)
                }

                val secondaryText = if (isDark) {
                    res.getColor(android.R.color.system_neutral2_200, theme)
                } else {
                    res.getColor(android.R.color.system_neutral2_700, theme)
                }

                val accent = if (isDark) {
                    res.getColor(android.R.color.system_accent1_200, theme)
                } else {
                    res.getColor(android.R.color.system_accent1_600, theme)
                }

                val stroke = if (isDark) {
                    res.getColor(android.R.color.system_neutral2_800, theme)
                } else {
                    res.getColor(android.R.color.system_neutral2_200, theme)
                }

                return Material3Palette(
                    backgroundColor = bg,
                    letterKeyColor = letterKey,
                    letterKeyColorPressed = letterKeyPressed,
                    functionalKeyColor = funcKey,
                    functionalKeyColorPressed = funcKeyPressed,
                    enterKeyColor = enterKey,
                    enterKeyColorPressed = enterKeyPressed,
                    enterTextColor = enterText,
                    textColor = text,
                    secondaryTextColor = secondaryText,
                    accentColor = accent,
                    strokeColor = stroke,
                    isDark = isDark
                )
            } else if (autoSync) {
                // Pre-Android 12 Material 3 Tonal Palette fallback
                val bg = if (isDark) Color.parseColor("#1B1B1F") else Color.parseColor("#FEF7FF")
                val letterKey = if (isDark) Color.parseColor("#2B2B30") else Color.WHITE
                val letterKeyPressed = if (isDark) Color.parseColor("#3B3B42") else Color.parseColor("#E6E0E9")
                val funcKey = if (isDark) Color.parseColor("#44464F") else Color.parseColor("#E7E0EC")
                val funcKeyPressed = if (isDark) Color.parseColor("#50525C") else Color.parseColor("#D0C9D4")
                val enterKey = if (isDark) Color.parseColor("#D0BCFF") else Color.parseColor("#6750A4")
                val enterKeyPressed = if (isDark) Color.parseColor("#E8DEF8") else Color.parseColor("#7D5260")
                val enterText = if (isDark) Color.parseColor("#381E72") else Color.WHITE
                val text = if (isDark) Color.parseColor("#E6E1E5") else Color.parseColor("#1D1B20")
                val secondaryText = if (isDark) Color.parseColor("#CAC4D0") else Color.parseColor("#49454F")
                val accent = if (isDark) Color.parseColor("#D0BCFF") else Color.parseColor("#6750A4")
                val stroke = if (isDark) Color.parseColor("#49454F") else Color.parseColor("#CAC4D0")

                return Material3Palette(
                    backgroundColor = bg,
                    letterKeyColor = letterKey,
                    letterKeyColorPressed = letterKeyPressed,
                    functionalKeyColor = funcKey,
                    functionalKeyColorPressed = funcKeyPressed,
                    enterKeyColor = enterKey,
                    enterKeyColorPressed = enterKeyPressed,
                    enterTextColor = enterText,
                    textColor = text,
                    secondaryTextColor = secondaryText,
                    accentColor = accent,
                    strokeColor = stroke,
                    isDark = isDark
                )
            } else {
                // User custom theme from settings
                val bg = context.safeStorageContext.getProperBackgroundColor()
                val text = context.safeStorageContext.getProperTextColor()
                val primary = context.safeStorageContext.getProperPrimaryColor()
                val letterKey = if (bg == Color.BLACK) Color.parseColor("#222222") else bg.lightenColor()
                val letterKeyPressed = letterKey.lightenColor()
                val funcKey = letterKey.darkenColor(if (isDark) 2 else -2)
                val funcKeyPressed = funcKey.lightenColor()
                val enterKey = primary
                val enterKeyPressed = primary.lightenColor()
                val enterText = if (isDark) Color.BLACK else Color.WHITE
                val secondaryText = text.adjustAlpha(0.7f)
                val stroke = bg.lightenColor()

                return Material3Palette(
                    backgroundColor = bg,
                    letterKeyColor = letterKey,
                    letterKeyColorPressed = letterKeyPressed,
                    functionalKeyColor = funcKey,
                    functionalKeyColorPressed = funcKeyPressed,
                    enterKeyColor = enterKey,
                    enterKeyColorPressed = enterKeyPressed,
                    enterTextColor = enterText,
                    textColor = text,
                    secondaryTextColor = secondaryText,
                    accentColor = primary,
                    strokeColor = stroke,
                    isDark = isDark
                )
            }
        }
    }
}
