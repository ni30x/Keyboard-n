package org.fossify.keyboard.views

import org.fossify.keyboard.helpers.MyKeyboard
import kotlin.math.exp
import kotlin.math.max

/**
 * Geometric model and spatial metrics for a single keyboard key.
 * Encapsulates drawn bounds, expanded gap-free touch bounds, offset-corrected center,
 * variance parameters, edge-distance calculation, and Gaussian spatial scoring.
 */
class KeyGeometry(
    val keyIndex: Int,
    val key: MyKeyboard.Key
) {
    // Drawn key bounds
    val drawnLeft: Float = key.x.toFloat()
    val drawnTop: Float = key.y.toFloat()
    val drawnRight: Float = (key.x + key.width).toFloat()
    val drawnBottom: Float = (key.y + key.height).toFloat()

    val width: Float = key.width.toFloat()
    val height: Float = key.height.toFloat()

    // Expanded touch bounds (tiled across visual gaps to cover entire keyboard area)
    var touchLeft: Float = drawnLeft
    var touchTop: Float = drawnTop
    var touchRight: Float = drawnRight
    var touchBottom: Float = drawnBottom

    // Geometric center of the key
    val centerX: Float = (drawnLeft + drawnRight) / 2f
    val centerY: Float = (drawnTop + drawnBottom) / 2f

    // Learned / adjustable center offsets (dx, dy) - default to zero, learned in Phase 4
    var offsetX: Float = 0f
    var offsetY: Float = 0f

    // Effective offset-corrected center
    val effectiveCenterX: Float
        get() = centerX + offsetX

    val effectiveCenterY: Float
        get() = centerY + offsetY

    // Gaussian variances (separate x and y variance)
    var sigmaX: Float = max(width * 0.50f, 1f)
    var sigmaY: Float = max(height * 0.50f, 1f)

    /**
     * Updates the center offset for this key.
     */
    fun setCenterOffset(dx: Float, dy: Float) {
        this.offsetX = dx
        this.offsetY = dy
    }

    /**
     * Calculates squared Euclidean distance from point (x, y) to the nearest edge
     * of this key's expanded touch region.
     * Returns 0f if (x, y) is inside the touch region.
     */
    fun squaredDistanceToEdge(x: Float, y: Float): Float {
        val dx = if (x < touchLeft) {
            touchLeft - x
        } else if (x > touchRight) {
            x - touchRight
        } else {
            0f
        }

        val dy = if (y < touchTop) {
            touchTop - y
        } else if (y > touchBottom) {
            y - touchBottom
        } else {
            0f
        }

        return dx * dx + dy * dy
    }

    /**
     * Computes Gaussian-style probability score based on distance from the key's
     * offset-corrected center with separate x and y variances.
     * Score is in range (0.0, 1.0].
     */
    fun computeGaussianScore(x: Float, y: Float): Float {
        val cdx = if (key.code == MyKeyboard.KEYCODE_SPACE) {
            // For space bar, clamp horizontal center reach so score remains high along the bar
            val innerMargin = width * 0.15f
            val clampedCx = x.coerceIn(drawnLeft + innerMargin, drawnRight - innerMargin)
            x - (clampedCx + offsetX)
        } else {
            x - effectiveCenterX
        }

        val cdy = y - effectiveCenterY

        val normDistSq = (cdx * cdx) / (sigmaX * sigmaX) + (cdy * cdy) / (sigmaY * sigmaY)
        return exp(-0.5f * normDistSq)
    }

    /**
     * Checks if point (x, y) is inside the expanded touch bounds.
     */
    fun isInsideTouchRegion(x: Float, y: Float): Boolean {
        return x >= touchLeft && x <= touchRight && y >= touchTop && y <= touchBottom
    }
}
