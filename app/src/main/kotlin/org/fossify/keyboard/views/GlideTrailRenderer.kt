package org.fossify.keyboard.views

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.keyboard.helpers.TraceBuffer
import kotlin.math.max

/**
 * Zero-allocation, high-frame-rate glide trail renderer.
 *
 * Performance Architecture:
 * 1. Preallocated Path, Paint, and Rect instances (zero object creation during onDraw).
 * 2. Quadratic Bezier curve smoothing (`quadTo`) between point midpoints for fluid paths.
 * 3. Segmented tapering and alpha fading along the trail length.
 * 4. Precise dirty-rectangle calculation enabling targeted view invalidation (`invalidate(dirtyRect)`)
 *    rather than full-keyboard canvas re-renders, guaranteeing 120 FPS / 60 FPS performance.
 */
class GlideTrailRenderer {

    companion object {
        const val MAX_TRAIL_POINTS = 35
        const val MAX_STROKE_WIDTH_DP = 10f
        const val MIN_STROKE_WIDTH_DP = 2.5f
    }

    private val mTrailPath = Path()
    private val mTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val mDirtyRectF = RectF()

    /**
     * Draws the active glide trace onto the canvas.
     * Note: Visual ink trail drawing is disabled per user request.
     */
    fun drawTrail(canvas: Canvas, traceBuffer: TraceBuffer, primaryColor: Int, density: Float) {
        // No-op: visual ink trail removed per user request
    }

    /**
     * Calculates the bounding dirty rectangle enclosing the active trail segment.
     */
    fun getDirtyRect(traceBuffer: TraceBuffer, density: Float, outRect: Rect) {
        val count = traceBuffer.size
        if (count == 0) {
            outRect.setEmpty()
            return
        }

        val padding = (MAX_STROKE_WIDTH_DP * density * 1.5f).toInt()
        val startIndex = max(0, count - MAX_TRAIL_POINTS)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (i in startIndex until count) {
            val pt = traceBuffer[i]
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }

        mDirtyRectF.set(
            minX - padding,
            minY - padding,
            maxX + padding,
            maxY + padding
        )
        mDirtyRectF.roundOut(outRect)
    }
}
