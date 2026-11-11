package org.fossify.keyboard.views

import org.fossify.keyboard.helpers.MyKeyboard
import kotlin.math.abs
import kotlin.math.max

/**
 * Result container holding the best key and top candidate keys with their Gaussian scores.
 * Kept reusable for zero allocations per touch event.
 */
class KeyDetectionResult {
    var bestKeyIndex: Int = -1
    var bestKey: MyKeyboard.Key? = null
    var bestScore: Float = 0f
    var bestDistEdgeSq: Float = Float.MAX_VALUE

    var runnerUpKeyIndex: Int = -1
    var runnerUpKey: MyKeyboard.Key? = null
    var runnerUpScore: Float = 0f
    var runnerUpDistEdgeSq: Float = Float.MAX_VALUE

    var thirdKeyIndex: Int = -1
    var thirdKey: MyKeyboard.Key? = null
    var thirdScore: Float = 0f
    var thirdDistEdgeSq: Float = Float.MAX_VALUE

    fun reset() {
        bestKeyIndex = -1
        bestKey = null
        bestScore = 0f
        bestDistEdgeSq = Float.MAX_VALUE

        runnerUpKeyIndex = -1
        runnerUpKey = null
        runnerUpScore = 0f
        runnerUpDistEdgeSq = Float.MAX_VALUE

        thirdKeyIndex = -1
        thirdKey = null
        thirdScore = 0f
        thirdDistEdgeSq = Float.MAX_VALUE
    }
}

/**
 * Gap-free, offset-aware key detector providing nearest-key assignment, spatial grid acceleration,
 * continuous boundary arbitration, and Gaussian spatial scoring.
 */
class KeyDetector(
    val keys: List<MyKeyboard.Key>,
    val keyboardWidth: Int,
    val keyboardHeight: Int,
    val spaceVerticalMarginFraction: Float = 0.20f,
    val wideKeyVerticalMarginFraction: Float = 0.10f
) {
    val keyGeometries: List<KeyGeometry>
    private val spatialGrid: SpatialGrid

    // Reusable result container for zero allocations during hit-testing
    private val reusableResult = KeyDetectionResult()

    init {
        keyGeometries = buildKeyGeometries()
        spatialGrid = SpatialGrid(keyboardWidth, keyboardHeight, keyGeometries)
    }

    /**
     * Builds tiled KeyGeometry instances ensuring all visual gaps are eliminated
     * and space/wide keys receive continuous extended vertical touch regions.
     */
    private fun buildKeyGeometries(): List<KeyGeometry> {
        val geometries = ArrayList<KeyGeometry>(keys.size)
        for (i in keys.indices) {
            geometries.add(KeyGeometry(i, keys[i]))
        }

        if (keys.isEmpty() || keyboardWidth <= 0 || keyboardHeight <= 0) {
            return geometries
        }

        // Group keys by row (keys within 16px vertical difference belong to same row)
        val rows = ArrayList<ArrayList<KeyGeometry>>()
        val sortedKeys = geometries.sortedWith(compareBy({ it.drawnTop }, { it.drawnLeft }))

        var currentRow = ArrayList<KeyGeometry>()
        var currentRowY = -1000f

        for (geom in sortedKeys) {
            if (currentRow.isEmpty() || abs(geom.drawnTop - currentRowY) < 16f) {
                currentRow.add(geom)
                if (currentRow.size == 1) {
                    currentRowY = geom.drawnTop
                }
            } else {
                rows.add(currentRow)
                currentRow = ArrayList()
                currentRow.add(geom)
                currentRowY = geom.drawnTop
            }
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }

        // 1. Horizontal tiling per row (eliminate horizontal gaps)
        for (row in rows) {
            row.sortBy { it.drawnLeft }
            val count = row.size
            if (count == 0) continue

            // Leftmost key tiles to left edge
            row[0].touchLeft = 0f

            // Intermediate keys meet at midpoints
            for (i in 0 until count - 1) {
                val current = row[i]
                val next = row[i + 1]
                val midX = (current.drawnRight + next.drawnLeft) / 2f
                current.touchRight = midX
                next.touchLeft = midX
            }

            // Rightmost key tiles to right edge
            row[count - 1].touchRight = keyboardWidth.toFloat()
        }

        // 2. Vertical tiling across rows (eliminate vertical gaps)
        val rowCount = rows.size
        if (rowCount > 0) {
            // First row tiles to top
            for (geom in rows[0]) {
                geom.touchTop = 0f
            }

            // Intermediate rows meet at midpoints
            for (r in 0 until rowCount - 1) {
                val rowCurrent = rows[r]
                val rowNext = rows[r + 1]

                val bottomCurrent = rowCurrent.maxOfOrNull { it.drawnBottom } ?: 0f
                val topNext = rowNext.minOfOrNull { it.drawnTop } ?: keyboardHeight.toFloat()
                val midY = (bottomCurrent + topNext) / 2f

                for (geom in rowCurrent) {
                    geom.touchBottom = midY
                }
                for (geom in rowNext) {
                    geom.touchTop = midY
                }
            }

            // Last row tiles to bottom
            for (geom in rows[rowCount - 1]) {
                geom.touchBottom = keyboardHeight.toFloat()
            }
        }

        // 3. Extended vertical touch area for space bar and wide keys
        val averageKeyWidth = keyboardWidth.toFloat() / 10f
        for (geom in geometries) {
            if (geom.key.code == MyKeyboard.KEYCODE_SPACE) {
                val extraMargin = geom.height * spaceVerticalMarginFraction
                geom.touchTop = (geom.touchTop - extraMargin).coerceAtLeast(0f)
                geom.touchBottom = (geom.touchBottom + extraMargin).coerceAtMost(keyboardHeight.toFloat())
            } else if (geom.width > averageKeyWidth * 1.5f) {
                val extraMargin = geom.height * wideKeyVerticalMarginFraction
                geom.touchTop = (geom.touchTop - extraMargin).coerceAtLeast(0f)
                geom.touchBottom = (geom.touchBottom + extraMargin).coerceAtMost(keyboardHeight.toFloat())
            }
        }

        return geometries
    }

    /**
     * Detects the best key index at coordinate (x, y). Zero object allocations.
     */
    fun detectBestKeyIndex(x: Float, y: Float): Int {
        val result = detectKey(x, y, reusableResult)
        return result.bestKeyIndex
    }

    /**
     * Hit-tests (x, y), filling outResult with best, runner-up, and third candidate keys along with scores.
     * Guaranteed zero allocations when using a passed-in or default reusable result.
     */
    fun detectKey(x: Float, y: Float, outResult: KeyDetectionResult? = null): KeyDetectionResult {
        val result = outResult ?: reusableResult
        result.reset()

        if (keyGeometries.isEmpty() || keyboardWidth <= 0 || keyboardHeight <= 0) {
            return result
        }

        // 1. Clamp touch coordinates to keyboard bounds so edges have no dead pixels
        val maxX = max(0f, keyboardWidth - 1f)
        val maxY = max(0f, keyboardHeight - 1f)
        val clampedX = x.coerceIn(0f, maxX)
        val clampedY = y.coerceIn(0f, maxY)

        // 2. Query spatial grid for candidate keys in cell
        val candidates = spatialGrid.getCandidateKeyIndices(clampedX, clampedY)
        val candidateCount = candidates.size

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        var bestScore = 0f

        var runnerUpIdx = -1
        var runnerUpDist = Float.MAX_VALUE
        var runnerUpScore = 0f

        var thirdIdx = -1
        var thirdDist = Float.MAX_VALUE
        var thirdScore = 0f

        for (i in 0 until candidateCount) {
            val kIdx = candidates[i]
            val geom = keyGeometries[kIdx]

            val dist = geom.squaredDistanceToEdge(clampedX, clampedY)
            val score = geom.computeGaussianScore(clampedX, clampedY)

            if (isCandidateBetter(dist, score, bestDist, bestScore)) {
                // Shift down: best -> runnerUp -> third
                thirdIdx = runnerUpIdx
                thirdDist = runnerUpDist
                thirdScore = runnerUpScore

                runnerUpIdx = bestIdx
                runnerUpDist = bestDist
                runnerUpScore = bestScore

                bestIdx = kIdx
                bestDist = dist
                bestScore = score
            } else if (isCandidateBetter(dist, score, runnerUpDist, runnerUpScore)) {
                // Shift down: runnerUp -> third
                thirdIdx = runnerUpIdx
                thirdDist = runnerUpDist
                thirdScore = runnerUpScore

                runnerUpIdx = kIdx
                runnerUpDist = dist
                runnerUpScore = score
            } else if (isCandidateBetter(dist, score, thirdDist, thirdScore)) {
                thirdIdx = kIdx
                thirdDist = dist
                thirdScore = score
            }
        }

        // Fallback: if somehow no candidate was found (e.g. empty cell), scan all keys
        if (bestIdx == -1 && keyGeometries.isNotEmpty()) {
            for (geom in keyGeometries) {
                val dist = geom.squaredDistanceToEdge(clampedX, clampedY)
                val score = geom.computeGaussianScore(clampedX, clampedY)
                if (isCandidateBetter(dist, score, bestDist, bestScore)) {
                    bestIdx = geom.keyIndex
                    bestDist = dist
                    bestScore = score
                }
            }
        }

        result.bestKeyIndex = bestIdx
        result.bestKey = if (bestIdx in keys.indices) keys[bestIdx] else null
        result.bestScore = bestScore
        result.bestDistEdgeSq = bestDist

        result.runnerUpKeyIndex = runnerUpIdx
        result.runnerUpKey = if (runnerUpIdx in keys.indices) keys[runnerUpIdx] else null
        result.runnerUpScore = runnerUpScore
        result.runnerUpDistEdgeSq = runnerUpDist

        result.thirdKeyIndex = thirdIdx
        result.thirdKey = if (thirdIdx in keys.indices) keys[thirdIdx] else null
        result.thirdScore = thirdScore
        result.thirdDistEdgeSq = thirdDist

        return result
    }

    /**
     * Determines whether candidate (distA, scoreA) is superior to candidate (distB, scoreB).
     * Nearest edge distance governs gap/neighbor assignment; Gaussian score breaks ties
     * and governs inside overlapping touch margins.
     */
    private fun isCandidateBetter(distA: Float, scoreA: Float, distB: Float, scoreB: Float): Boolean {
        if (distB == Float.MAX_VALUE) return true

        val aInside = distA <= 0.001f
        val bInside = distB <= 0.001f

        if (aInside != bInside) {
            return aInside
        }

        if (aInside && bInside) {
            // Both are inside touch region: Gaussian center score breaks tie
            return scoreA > scoreB
        }

        // Both are outside: compare edge distance first
        val diff = distA - distB
        return if (abs(diff) < 1.0f) {
            scoreA > scoreB
        } else {
            diff < 0f
        }
    }

    /**
     * Sets per-key center offset (dx, dy). Used in Phase 4 for learned tap centering.
     */
    fun setKeyCenterOffset(keyIndex: Int, dx: Float, dy: Float) {
        if (keyIndex in keyGeometries.indices) {
            keyGeometries[keyIndex].setCenterOffset(dx, dy)
        }
    }

    /**
     * Resets all learned key center offsets to zero.
     */
    fun resetAllCenterOffsets() {
        for (geom in keyGeometries) {
            geom.setCenterOffset(0f, 0f)
        }
    }

    /**
     * Determines the row index (0-based from top to bottom) for a given key.
     */
    fun getRowIndex(key: MyKeyboard.Key): Int {
        var r = 0
        var lastY = -1000f
        val targetY = key.y.toFloat()
        for (geom in keyGeometries) {
            if (geom.drawnTop - lastY > 16f) {
                if (abs(geom.drawnTop - targetY) < 16f) {
                    return r
                }
                lastY = geom.drawnTop
                r++
            }
        }
        return (key.y / max(key.height, 1)).coerceAtLeast(0)
    }
}
