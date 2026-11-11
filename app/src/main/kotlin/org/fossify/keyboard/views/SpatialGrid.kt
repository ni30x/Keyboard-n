package org.fossify.keyboard.views

/**
 * Precomputed 2D spatial acceleration grid partitioning the keyboard surface
 * into uniform cells. Each cell contains preallocated primitive indices of nearby keys.
 * Enables O(1) candidate lookup per touch without scanning all keys.
 */
class SpatialGrid(
    val keyboardWidth: Int,
    val keyboardHeight: Int,
    val keyGeometries: List<KeyGeometry>,
    val gridColumns: Int = 12,
    val gridRows: Int = 6
) {
    private val cellWidth: Float = if (gridColumns > 0 && keyboardWidth > 0) {
        keyboardWidth.toFloat() / gridColumns
    } else {
        1f
    }

    private val cellHeight: Float = if (gridRows > 0 && keyboardHeight > 0) {
        keyboardHeight.toFloat() / gridRows
    } else {
        1f
    }

    // Preallocated candidate key index arrays per cell [row][col]
    private val grid: Array<Array<IntArray>>

    // All key indices as fallback
    private val allKeyIndices: IntArray = IntArray(keyGeometries.size) { it }

    init {
        // Temporary mutable accumulator
        val tempGrid = Array(gridRows) {
            Array(gridColumns) { ArrayList<Int>(8) }
        }

        // For each key geometry, determine overlapping cell range
        for (geom in keyGeometries) {
            val kIdx = geom.keyIndex

            // Expand reach by half cell to guarantee border proximity coverage
            val reachX = cellWidth * 0.5f
            val reachY = cellHeight * 0.5f

            val minX = (geom.touchLeft - reachX).coerceIn(0f, keyboardWidth.toFloat())
            val maxX = (geom.touchRight + reachX).coerceIn(0f, keyboardWidth.toFloat())
            val minY = (geom.touchTop - reachY).coerceIn(0f, keyboardHeight.toFloat())
            val maxY = (geom.touchBottom + reachY).coerceIn(0f, keyboardHeight.toFloat())

            val startCol = (minX / cellWidth).toInt().coerceIn(0, gridColumns - 1)
            val endCol = (maxX / cellWidth).toInt().coerceIn(0, gridColumns - 1)
            val startRow = (minY / cellHeight).toInt().coerceIn(0, gridRows - 1)
            val endRow = (maxY / cellHeight).toInt().coerceIn(0, gridRows - 1)

            for (r in startRow..endRow) {
                for (c in startCol..endCol) {
                    val cellList = tempGrid[r][c]
                    if (!cellList.contains(kIdx)) {
                        cellList.add(kIdx)
                    }
                }
            }
        }

        // Freeze into flat primitive IntArrays for zero-allocation runtime queries
        grid = Array(gridRows) { r ->
            Array(gridColumns) { c ->
                val list = tempGrid[r][c]
                if (list.isEmpty()) {
                    allKeyIndices
                } else {
                    IntArray(list.size) { list[it] }
                }
            }
        }
    }

    /**
     * Retrieves preallocated array of candidate key indices for the given coordinates.
     * Guaranteed zero heap allocations during touch events.
     */
    fun getCandidateKeyIndices(clampedX: Float, clampedY: Float): IntArray {
        if (gridColumns <= 0 || gridRows <= 0) return allKeyIndices

        val col = (clampedX / cellWidth).toInt().coerceIn(0, gridColumns - 1)
        val row = (clampedY / cellHeight).toInt().coerceIn(0, gridRows - 1)

        return grid[row][col]
    }
}
