package org.fossify.keyboard.extensions

import android.content.Context
import android.graphics.Color
import org.fossify.commons.extensions.darkenColor
import org.fossify.keyboard.R
import org.fossify.keyboard.helpers.Material3Palette

fun Context.getKeyboardBackgroundColor(): Int {
    val palette = Material3Palette.resolve(this)
    val color = palette.backgroundColor

    // use darker background color when key borders are enabled
    if (config.showKeyBorders) {
        val darkerColor = color.darkenColor(2)
        return if (darkerColor == Color.WHITE) {
            resources.getColor(R.color.md_grey_200, theme)
        } else {
            darkerColor
        }
    }

    return color
}

fun Context.getStrokeColor(): Int {
    val palette = Material3Palette.resolve(this)
    return palette.strokeColor
}
