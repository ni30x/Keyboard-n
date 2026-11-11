package org.fossify.keyboard.views

import android.graphics.Rect
import android.graphics.Typeface
import org.fossify.keyboard.helpers.MyKeyboard

/**
 * Precomputed layout and typography metrics for a single keyboard key.
 * All measurements, baselines, and boundaries are cached here to guarantee
 * zero text measurements and zero allocations during frame rendering.
 */
class KeyRenderInfo(
    val keyIndex: Int,
    val key: MyKeyboard.Key
) {
    // Key bounds and invalidation rect
    val keyRect = Rect()
    val dirtyRect = Rect()

    // Primary label precomputed drawing parameters
    var label: String = ""
    var labelX: Float = 0f
    var labelY: Float = 0f
    var labelTextSize: Float = 0f
    var labelTypeface: Typeface? = null
    var isMultilineLabel: Boolean = false
    var multilineRows: Array<String> = emptyArray()
    var multilineRowY: FloatArray = FloatArray(0)

    // Sublabel (e.g. on numpad or symbol keys)
    var hasSublabel: Boolean = false
    var sublabel: String = ""
    var sublabelX: Float = 0f
    var sublabelY: Float = 0f
    var sublabelTextSize: Float = 0f

    // Top small number / secondary label
    var hasTopNumber: Boolean = false
    var topNumber: String = ""
    var topNumberX: Float = 0f
    var topNumberY: Float = 0f

    // Icons
    var hasIcon: Boolean = false
    val iconBounds = Rect()
    var hasSecondaryIcon: Boolean = false
    val secondaryIconBounds = Rect()

    // Key styling and type flags
    var isEnterKey: Boolean = false
    var isSpaceKey: Boolean = false
    var isShiftKey: Boolean = false
    var isDeleteKey: Boolean = false
    var isPillKey: Boolean = false
    val isFunctionKey: Boolean get() = isEnterKey || isSpaceKey || isShiftKey || isDeleteKey || isPillKey || key.code < 0 || key.icon != null

    // Release fade animation tracking
    var releaseFadeStartTime: Long = 0L
    var isFading: Boolean = false
}
