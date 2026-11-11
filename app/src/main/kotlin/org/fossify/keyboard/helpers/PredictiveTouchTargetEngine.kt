package org.fossify.keyboard.helpers

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * Predictive touch target engine that enhances typing accuracy and touch smoothness.
 * Dynamically predicts likely next characters based on the currently composing word prefix,
 * inflating effective invisible touch hitboxes and using Gaussian distance scoring to cleanly
 * resolve taps falling into gaps or near key boundaries.
 */
object PredictiveTouchTargetEngine {

    @Volatile
    private var currentPrefix: String = ""

    // Baseline initial letter frequency distribution for English
    private val INITIAL_LETTER_WEIGHTS = floatArrayOf(
        1.8f, // a
        1.4f, // b
        1.5f, // c
        1.3f, // d
        1.3f, // e
        1.3f, // f
        1.2f, // g
        1.4f, // h
        1.6f, // i
        1.0f, // j
        1.0f, // k
        1.3f, // l
        1.4f, // m
        1.2f, // n
        1.4f, // o
        1.5f, // p
        0.8f, // q
        1.3f, // r
        1.8f, // s
        2.0f, // t
        1.1f, // u
        1.0f, // v
        1.5f, // w
        0.7f, // x
        1.1f, // y
        0.7f  // z
    )

    // Cached probability array for 'a'..'z' (indices 0..25)
    private val cachedProbabilities = FloatArray(26) { INITIAL_LETTER_WEIGHTS[it] }

    /**
     * Updates the current word prefix being typed and recomputes predicted next letter probabilities.
     */
    fun updateComposingPrefix(prefix: String) {
        val trimmed = prefix.trim()
        currentPrefix = trimmed

        if (trimmed.isEmpty() || !trimmed.any { it.isLetter() }) {
            // Reset to initial letter distribution
            for (i in 0 until 26) {
                cachedProbabilities[i] = INITIAL_LETTER_WEIGHTS[i]
            }
            return
        }

        val frequencies = IntArray(26)
        SuggestionEngine.fillNextCharFrequencies(trimmed, frequencies)

        var maxFreq = 0
        for (f in frequencies) {
            if (f > maxFreq) maxFreq = f
        }

        if (maxFreq > 0) {
            val maxFreqFloat = maxFreq.toFloat()
            for (i in 0 until 26) {
                val f = frequencies[i]
                if (f > 0) {
                    // Moderate expansion: predicted characters scale between 1.0f and 2.5f
                    cachedProbabilities[i] = 1.0f + 1.5f * (f / maxFreqFloat)
                } else {
                    // Non-continuation letters slightly dampened to 0.7f
                    cachedProbabilities[i] = 0.7f
                }
            }
        } else {
            // No dictionary continuations found (rare word / typo / code) - flat baseline
            for (i in 0 until 26) {
                cachedProbabilities[i] = 1.0f
            }
        }
    }

    /**
     * Gets probability weight for the given key code.
     */
    fun getCharProbability(code: Int): Float {
        val ch = code.toChar().lowercaseChar()
        return if (ch in 'a'..'z') {
            cachedProbabilities[ch - 'a']
        } else {
            1.0f
        }
    }

    /**
     * Resolves touch coordinate (x, y) to the best matching key index using continuous
     * Voronoi tessellation and Bivariate Gaussian distance scoring weighted by predicted
     * letter probability. Guarantees zero dead zones across the keyboard canvas, seamlessly
     * resolving taps in inter-key gaps, borders, and margins.
     */
    fun resolvePressedKeyIndex(keys: List<MyKeyboard.Key>, x: Int, y: Int): Int {
        if (keys.isEmpty()) return -1

        // Phase 1: High-confidence direct core hit check (inner 60% of key)
        for (i in keys.indices) {
            val key = keys[i]
            val halfW = key.width / 2f
            val halfH = key.height / 2f
            val cx = key.x + halfW
            val cy = key.y + halfH

            if (key.code == MyKeyboard.KEYCODE_SPACE) {
                if (x >= key.x && x < key.x + key.width && y >= key.y && y < key.y + key.height) {
                    return i
                }
            } else {
                val dx = abs(x - cx)
                val dy = abs(y - cy)
                if (dx <= halfW * 0.60f && dy <= halfH * 0.60f) {
                    return i
                }
            }
        }

        // Phase 2: Continuous Voronoi & Bivariate Gaussian spatial model
        // Evaluates every point in the keyboard plane (including gaps and margins)
        var bestIndex = -1
        var bestScore = -1.0f

        var nearestVoronoiIndex = -1
        var minSquaredDistance = Float.MAX_VALUE

        for (i in keys.indices) {
            val key = keys[i]
            val halfW = key.width / 2f
            val halfH = key.height / 2f
            val cx = key.x + halfW
            val cy = key.y + halfH

            val dx: Float
            val dy: Float

            if (key.code == MyKeyboard.KEYCODE_SPACE) {
                val clampedX = x.coerceIn(key.x, key.x + key.width).toFloat()
                dx = x - clampedX
                dy = y - cy
            } else {
                dx = x - cx
                dy = y - cy
            }

            // Track continuous Euclidean Voronoi distance to key centroid
            val distSq = dx * dx + dy * dy
            if (distSq < minSquaredDistance) {
                minSquaredDistance = distSq
                nearestVoronoiIndex = i
            }

            // Bivariate Gaussian distribution parameters
            val sigmaX = key.width * 0.55f
            val sigmaY = key.height * 0.55f

            val normDistSq = (dx * dx) / (sigmaX * sigmaX) + (dy * dy) / (sigmaY * sigmaY)
            val spatialGaussian = exp(-0.5f * normDistSq)

            // Letter prior probability from language model
            val priorProb = getCharProbability(key.code)

            // Soft inside-bounding-box affinity bonus
            val insideX = x >= key.x && x < key.x + key.width
            val insideY = y >= key.y && y < key.y + key.height
            val insideBonus = if (insideX && insideY) 1.25f else 1.0f

            // Functional keys (Enter, Backspace, Space, Shift) get distinct weighting
            val functionKeyBonus = if (key.code < 0 || key.code == MyKeyboard.KEYCODE_SPACE || key.code == MyKeyboard.KEYCODE_ENTER) 1.15f else 1.0f

            val score = spatialGaussian * priorProb * insideBonus * functionKeyBonus
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }

        // Phase 3: Guaranteed Voronoi fallback (ensures zero dead space on gaps or outer edges)
        if (bestIndex == -1 || bestScore <= 0.001f) {
            return if (nearestVoronoiIndex != -1) nearestVoronoiIndex else 0
        }

        return bestIndex
    }
}
