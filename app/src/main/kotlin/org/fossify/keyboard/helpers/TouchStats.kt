package org.fossify.keyboard.helpers

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Numerically stable, incremental 2D touch statistics tracking running normalized
 * offsets (dx / keyWidth, dy / keyHeight) using Welford's algorithm.
 *
 * Implements exponential decay over time (default half-life: 7 days) to automatically
 * forget stale typing postures and adapt to new grips, cases, or hand sizes.
 * Stores zero historical sample lists: memory usage is strictly O(1).
 */
class TouchStats(
    var count: Double = 0.0,
    var meanX: Double = 0.0,
    var meanY: Double = 0.0,
    var m2X: Double = 0.0,
    var m2Y: Double = 0.0,
    var lastUpdateTime: Long = System.currentTimeMillis(),
    val halfLifeMs: Long = DEFAULT_HALF_LIFE_MS
) {
    companion object {
        const val DEFAULT_HALF_LIFE_MS: Long = 7L * 24L * 3600L * 1000L // 7 days

        fun fromJson(json: JSONObject): TouchStats {
            return TouchStats(
                count = json.optDouble("count", 0.0),
                meanX = json.optDouble("meanX", 0.0),
                meanY = json.optDouble("meanY", 0.0),
                m2X = json.optDouble("m2X", 0.0),
                m2Y = json.optDouble("m2Y", 0.0),
                lastUpdateTime = json.optLong("lastUpdateTime", System.currentTimeMillis())
            )
        }
    }

    /**
     * Applies exponential time decay based on elapsed time since last update.
     */
    fun applyDecay(currentTimeMs: Long = System.currentTimeMillis()) {
        if (count <= 0.0 || halfLifeMs <= 0) {
            lastUpdateTime = currentTimeMs
            return
        }

        val elapsed = currentTimeMs - lastUpdateTime
        if (elapsed > 0) {
            val lambda = ln(2.0) / halfLifeMs.toDouble()
            val decay = exp(-lambda * elapsed.toDouble())
            count *= decay
            m2X *= decay
            m2Y *= decay
            lastUpdateTime = currentTimeMs
        }
    }

    /**
     * Incrementally incorporates a new normalized offset sample (dx, dy).
     */
    fun addSample(dx: Double, dy: Double, timestampMs: Long = System.currentTimeMillis()) {
        applyDecay(timestampMs)

        count += 1.0
        val deltaX = dx - meanX
        meanX += deltaX / count
        val deltaX2 = dx - meanX
        m2X += deltaX * deltaX2

        val deltaY = dy - meanY
        meanY += deltaY / count
        val deltaY2 = dy - meanY
        m2Y += deltaY * deltaY2
    }

    val varianceX: Double
        get() = if (count > 1.0) max(m2X / (count - 1.0), 0.0001) else 0.05

    val varianceY: Double
        get() = if (count > 1.0) max(m2Y / (count - 1.0), 0.0001) else 0.05

    val stdDevX: Double
        get() = sqrt(varianceX)

    val stdDevY: Double
        get() = sqrt(varianceY)

    fun reset() {
        count = 0.0
        meanX = 0.0
        meanY = 0.0
        m2X = 0.0
        m2Y = 0.0
        lastUpdateTime = System.currentTimeMillis()
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("count", count)
            put("meanX", meanX)
            put("meanY", meanY)
            put("m2X", m2X)
            put("m2Y", m2Y)
            put("lastUpdateTime", lastUpdateTime)
        }
    }
}
