package org.fossify.keyboard.helpers

/**
 * Single historical motion coordinate sample captured during a glide trace gesture.
 */
class TracePoint(
    var x: Float = 0f,
    var y: Float = 0f,
    var timeMs: Long = 0L
) {
    fun set(x: Float, y: Float, timeMs: Long) {
        this.x = x
        this.y = y
        this.timeMs = timeMs
    }

    fun copy(): TracePoint = TracePoint(x, y, timeMs)
}

/**
 * Preallocated, fixed-capacity circular buffer storing [TracePoint] samples
 * for glide trace tracking without runtime object allocations.
 */
class TraceBuffer(val capacity: Int = 500) {
    private val buffer = Array(capacity) { TracePoint() }
    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float, timeMs: Long) {
        if (size < capacity) {
            buffer[size].set(x, y, timeMs)
            size++
        } else {
            // Shift elements left when capacity is reached
            System.arraycopy(buffer, 1, buffer, 0, capacity - 1)
            buffer[capacity - 1].set(x, y, timeMs)
        }
    }

    operator fun get(index: Int): TracePoint {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }
        return buffer[index]
    }

    val lastX: Float
        get() = if (size > 0) buffer[size - 1].x else 0f

    val lastY: Float
        get() = if (size > 0) buffer[size - 1].y else 0f

    fun toList(): List<TracePoint> {
        val list = ArrayList<TracePoint>(size)
        for (i in 0 until size) {
            list.add(buffer[i].copy())
        }
        return list
    }
}
