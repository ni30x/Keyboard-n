package org.fossify.keyboard.helpers

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Three-level hierarchical spatial offset model:
 * 1. Global level: overall touch skew across the entire keyboard.
 * 2. Row/cluster level: systematic vertical or horizontal shift for a specific key row.
 * 3. Per-key level: fine-grained target offset for individual letters.
 *
 * Implements sample-count threshold backoff: more specific levels are used only
 * when they meet the sample requirement (default N = 50), otherwise backing off
 * gracefully to the row or global level.
 *
 * Enforces strict safety clamping (max ~35% of key dimension) to prevent drift.
 * Spacebar uses tailored asymmetric clamping (strict horizontal, extended upward vertical).
 */
class HierarchicalOffsetModel(
    val modelId: String,
    val minSamplesThreshold: Double = DEFAULT_MIN_SAMPLES
) {
    companion object {
        const val DEFAULT_MIN_SAMPLES: Double = 50.0
        const val MAX_NORMAL_OFFSET_FRACTION: Double = 0.35
        const val MAX_SPACE_X_FRACTION: Double = 0.08
        const val MIN_SPACE_Y_FRACTION: Double = -0.45 // Allow learning taps landing above spacebar
        const val MAX_SPACE_Y_FRACTION: Double = 0.15

        fun fromJson(modelId: String, json: JSONObject): HierarchicalOffsetModel {
            val model = HierarchicalOffsetModel(
                modelId = modelId,
                minSamplesThreshold = json.optDouble("minSamplesThreshold", DEFAULT_MIN_SAMPLES)
            )

            if (json.has("global")) {
                model.globalStats = TouchStats.fromJson(json.getJSONObject("global"))
            }

            val rowsObj = json.optJSONObject("rows")
            if (rowsObj != null) {
                val keys = rowsObj.keys()
                while (keys.hasNext()) {
                    val rKey = keys.next()
                    val rIdx = rKey.toIntOrNull() ?: continue
                    model.rowStats[rIdx] = TouchStats.fromJson(rowsObj.getJSONObject(rKey))
                }
            }

            val keysObj = json.optJSONObject("keys")
            if (keysObj != null) {
                val keys = keysObj.keys()
                while (keys.hasNext()) {
                    val kCodeStr = keys.next()
                    val kCode = kCodeStr.toIntOrNull() ?: continue
                    model.keyStats[kCode] = TouchStats.fromJson(keysObj.getJSONObject(kCodeStr))
                }
            }

            return model
        }
    }

    var globalStats = TouchStats()
    val rowStats = ConcurrentHashMap<Int, TouchStats>()
    val keyStats = ConcurrentHashMap<Int, TouchStats>()

    /**
     * Incrementally records a committed key tap.
     */
    fun recordTap(
        keyCode: Int,
        rowIndex: Int,
        normalizedDx: Double,
        normalizedDy: Double,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        globalStats.addSample(normalizedDx, normalizedDy, timestampMs)

        val rStats = rowStats.getOrPut(rowIndex) { TouchStats() }
        rStats.addSample(normalizedDx, normalizedDy, timestampMs)

        val kStats = keyStats.getOrPut(keyCode) { TouchStats() }
        kStats.addSample(normalizedDx, normalizedDy, timestampMs)
    }

    /**
     * Resolves the learned pixel offset (dx, dy) for a key using hierarchical backoff.
     */
    fun getOffset(
        keyCode: Int,
        rowIndex: Int,
        keyWidth: Float,
        keyHeight: Float,
        isSpace: Boolean = false
    ): Pair<Float, Float> {
        val kStats = keyStats[keyCode]
        val rStats = rowStats[rowIndex]

        // Hierarchical selection with backoff
        val (rawNdx, rawNdy) = when {
            kStats != null && kStats.count >= minSamplesThreshold -> {
                Pair(kStats.meanX, kStats.meanY)
            }
            rStats != null && rStats.count >= minSamplesThreshold -> {
                Pair(rStats.meanX, rStats.meanY)
            }
            globalStats.count >= minSamplesThreshold -> {
                Pair(globalStats.meanX, globalStats.meanY)
            }
            else -> {
                Pair(0.0, 0.0)
            }
        }

        // Apply clamping
        val clampedNdx: Double
        val clampedNdy: Double

        if (isSpace) {
            clampedNdx = rawNdx.coerceIn(-MAX_SPACE_X_FRACTION, MAX_SPACE_X_FRACTION)
            clampedNdy = rawNdy.coerceIn(MIN_SPACE_Y_FRACTION, MAX_SPACE_Y_FRACTION)
        } else {
            clampedNdx = rawNdx.coerceIn(-MAX_NORMAL_OFFSET_FRACTION, MAX_NORMAL_OFFSET_FRACTION)
            clampedNdy = rawNdy.coerceIn(-MAX_NORMAL_OFFSET_FRACTION, MAX_NORMAL_OFFSET_FRACTION)
        }

        val px = (clampedNdx * keyWidth).toFloat()
        val py = (clampedNdy * keyHeight).toFloat()
        return Pair(px, py)
    }

    /**
     * Resolves the learned spatial variances for Gaussian scoring.
     */
    fun getSigma(
        keyCode: Int,
        rowIndex: Int,
        keyWidth: Float,
        keyHeight: Float
    ): Pair<Float, Float> {
        val kStats = keyStats[keyCode]
        val rStats = rowStats[rowIndex]

        val (varNdx, varNdy) = when {
            kStats != null && kStats.count >= minSamplesThreshold -> Pair(kStats.varianceX, kStats.varianceY)
            rStats != null && rStats.count >= minSamplesThreshold -> Pair(rStats.varianceX, rStats.varianceY)
            globalStats.count >= minSamplesThreshold -> Pair(globalStats.varianceX, globalStats.varianceY)
            else -> Pair(0.25, 0.25)
        }

        val sigmaX = max((Math.sqrt(varNdx) * keyWidth).toFloat(), keyWidth * 0.35f)
        val sigmaY = max((Math.sqrt(varNdy) * keyHeight).toFloat(), keyHeight * 0.35f)
        return Pair(sigmaX, sigmaY)
    }

    fun getSampleCount(keyCode: Int): Double {
        return keyStats[keyCode]?.count ?: 0.0
    }

    fun getTotalSamples(): Double {
        return globalStats.count
    }

    fun reset() {
        globalStats.reset()
        rowStats.clear()
        keyStats.clear()
    }

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("minSamplesThreshold", minSamplesThreshold)
        root.put("global", globalStats.toJson())

        val rowsObj = JSONObject()
        for ((r, stat) in rowStats) {
            rowsObj.put(r.toString(), stat.toJson())
        }
        root.put("rows", rowsObj)

        val keysObj = JSONObject()
        for ((k, stat) in keyStats) {
            keysObj.put(k.toString(), stat.toJson())
        }
        root.put("keys", keysObj)

        return root
    }
}
