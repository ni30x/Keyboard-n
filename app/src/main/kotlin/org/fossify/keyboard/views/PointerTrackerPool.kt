package org.fossify.keyboard.views

import android.view.MotionEvent

/**
 * Preallocated pool of [PointerTracker]s managing pointer-to-tracker mapping,
 * batched historical events consumption, touch-noise suppression, and two-thumb rollover.
 */
class PointerTrackerPool(
    private val delegate: PointerTrackerDelegate
) {
    companion object {
        const val MAX_TRACKERS = 8
        private const val MAX_POINTER_ID = 32
        const val DEFAULT_TOUCH_NOISE_TIME_THRESHOLD_MS = 40L
        const val DEFAULT_TOUCH_NOISE_DISTANCE_THRESHOLD_PX = 25f
        const val DEFAULT_HYSTERESIS_FRACTION = 0.25f
        const val DEFAULT_PHANTOM_MOVE_FRACTION = 0.25f
    }

    // Preallocated tracker objects (supports at least 4 pointers, pool of 8)
    private val trackers = Array(MAX_TRACKERS) { index ->
        PointerTracker(index, delegate)
    }

    // Mapping from MotionEvent pointerId (0..31) to tracker index in `trackers`
    private val pointerIdToTrackerIndex = IntArray(MAX_POINTER_ID) { -1 }

    // Active pointers stack tracking arrival order for rollover
    private val activePointerIds = IntArray(MAX_TRACKERS) { -1 }
    private var activePointerCount = 0

    // Touch noise filter tracking
    private var lastUpTime: Long = 0L
    private var lastUpX: Float = -1000f
    private var lastUpY: Float = -1000f

    /**
     * Finds or allocates a preallocated tracker for the given pointerId.
     */
    fun getTracker(pointerId: Int): PointerTracker? {
        if (pointerId in 0 until MAX_POINTER_ID) {
            val idx = pointerIdToTrackerIndex[pointerId]
            if (idx in 0 until MAX_TRACKERS) {
                return trackers[idx]
            }
        }
        return null
    }

    private fun allocateTracker(pointerId: Int): PointerTracker? {
        if (pointerId !in 0 until MAX_POINTER_ID) return null

        val existingIdx = pointerIdToTrackerIndex[pointerId]
        if (existingIdx in 0 until MAX_TRACKERS) {
            return trackers[existingIdx]
        }

        // Find an idle tracker
        for (i in 0 until MAX_TRACKERS) {
            val tracker = trackers[i]
            if (!tracker.isDown && tracker.pointerId == -1) {
                tracker.bind(pointerId)
                pointerIdToTrackerIndex[pointerId] = i
                return tracker
            }
        }

        // Fallback: any tracker that is not down
        for (i in 0 until MAX_TRACKERS) {
            val tracker = trackers[i]
            if (!tracker.isDown) {
                if (tracker.pointerId in 0 until MAX_POINTER_ID) {
                    pointerIdToTrackerIndex[tracker.pointerId] = -1
                }
                tracker.bind(pointerId)
                pointerIdToTrackerIndex[pointerId] = i
                return tracker
            }
        }

        return null
    }

    private fun releaseTrackerBinding(pointerId: Int) {
        if (pointerId in 0 until MAX_POINTER_ID) {
            val idx = pointerIdToTrackerIndex[pointerId]
            if (idx in 0 until MAX_TRACKERS) {
                trackers[idx].reset()
            }
            pointerIdToTrackerIndex[pointerId] = -1
        }
        removeActivePointer(pointerId)
    }

    private fun addActivePointer(pointerId: Int) {
        removeActivePointer(pointerId)
        if (activePointerCount < MAX_TRACKERS) {
            activePointerIds[activePointerCount++] = pointerId
        }
    }

    private fun removeActivePointer(pointerId: Int) {
        var foundIndex = -1
        for (i in 0 until activePointerCount) {
            if (activePointerIds[i] == pointerId) {
                foundIndex = i
                break
            }
        }
        if (foundIndex != -1) {
            for (i in foundIndex until activePointerCount - 1) {
                activePointerIds[i] = activePointerIds[i + 1]
            }
            activePointerIds[--activePointerCount] = -1
        }
    }

    /**
     * Rollover: when a newer pointer is released while an older pointer is still down,
     * give the older one a "virtual release" so keys register in press order.
     */
    private fun processRollover(releasingPointerId: Int, eventTime: Long) {
        var releasingIndex = -1
        for (i in 0 until activePointerCount) {
            if (activePointerIds[i] == releasingPointerId) {
                releasingIndex = i
                break
            }
        }

        if (releasingIndex > 0) {
            // Older pointers arrived before this one (index 0 until releasingIndex)
            for (i in 0 until releasingIndex) {
                val olderPointerId = activePointerIds[i]
                val olderTracker = getTracker(olderPointerId)
                if (olderTracker != null && olderTracker.isDown && !olderTracker.hasVirtualReleased) {
                    olderTracker.onVirtualRelease(eventTime)
                }
            }
        }
    }

    /**
     * Touch-noise filter: ignore a new DOWN that happens within a very short time
     * and tiny distance of the previous UP.
     */
    private fun isTouchNoise(x: Float, y: Float, eventTime: Long): Boolean {
        val timeDiff = eventTime - lastUpTime
        val timeThreshold = delegate.getTouchNoiseTimeThresholdMs()
        if (timeDiff in 0 until timeThreshold) {
            val dx = x - lastUpX
            val dy = y - lastUpY
            val distSq = dx * dx + dy * dy
            val distThreshold = delegate.getTouchNoiseDistanceThresholdPx()
            if (distSq < distThreshold * distThreshold) {
                return true
            }
        }
        return false
    }

    /**
     * Main touch processing method with zero allocations in onTouchEvent.
     */
    fun processTouchEvent(event: MotionEvent, verticalCorrection: Int): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex
        val eventTime = event.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val pointerId = event.getPointerId(0)
                val x = event.getX(0)
                var y = event.getY(0)
                if (y >= -verticalCorrection) {
                    y += verticalCorrection
                }

                if (isTouchNoise(x, y, eventTime)) {
                    return true
                }

                val tracker = allocateTracker(pointerId)
                if (tracker != null) {
                    addActivePointer(pointerId)
                    tracker.onDown(x, y, eventTime)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                delegate.glideTraceTracker?.cancel()
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                var y = event.getY(actionIndex)
                if (y >= -verticalCorrection) {
                    y += verticalCorrection
                }

                if (isTouchNoise(x, y, eventTime)) {
                    return true
                }

                val tracker = allocateTracker(pointerId)
                if (tracker != null) {
                    addActivePointer(pointerId)
                    tracker.onDown(x, y, eventTime)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val historySize = event.historySize
                val pointerCount = event.pointerCount

                // 1. Consume ALL historical samples from batched MOVE events in time order
                for (h in 0 until historySize) {
                    val histTime = event.getHistoricalEventTime(h)
                    for (p in 0 until pointerCount) {
                        val pointerId = event.getPointerId(p)
                        val tracker = getTracker(pointerId)
                        if (tracker != null && tracker.isDown) {
                            val hx = event.getHistoricalX(p, h)
                            var hy = event.getHistoricalY(p, h)
                            if (hy >= -verticalCorrection) {
                                hy += verticalCorrection
                            }
                            tracker.onMove(hx, hy, histTime, event)
                        }
                    }
                }

                // 2. Consume current sample
                for (p in 0 until pointerCount) {
                    val pointerId = event.getPointerId(p)
                    val tracker = getTracker(pointerId)
                    if (tracker != null && tracker.isDown) {
                        val cx = event.getX(p)
                        var cy = event.getY(p)
                        if (cy >= -verticalCorrection) {
                            cy += verticalCorrection
                        }
                        tracker.onMove(cx, cy, eventTime, event)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                var y = event.getY(actionIndex)
                if (y >= -verticalCorrection) {
                    y += verticalCorrection
                }

                // Rollover: if newer pointer is released, older pointer gets virtual release
                processRollover(pointerId, eventTime)

                val tracker = getTracker(pointerId)
                if (tracker != null) {
                    tracker.onUp(x, y, eventTime)
                    releaseTrackerBinding(pointerId)
                }

                lastUpTime = eventTime
                lastUpX = x
                lastUpY = y
            }

            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(0)
                val x = event.getX(0)
                var y = event.getY(0)
                if (y >= -verticalCorrection) {
                    y += verticalCorrection
                }

                // Rollover: if newer pointer is released, older pointer gets virtual release
                processRollover(pointerId, eventTime)

                val tracker = getTracker(pointerId)
                if (tracker != null) {
                    tracker.onUp(x, y, eventTime)
                    releaseTrackerBinding(pointerId)
                }

                // Clean up any remaining trackers
                for (i in 0 until MAX_TRACKERS) {
                    if (trackers[i].isDown) {
                        trackers[i].onUp(x, y, eventTime)
                    }
                    trackers[i].reset()
                }
                pointerIdToTrackerIndex.fill(-1)
                activePointerIds.fill(-1)
                activePointerCount = 0

                lastUpTime = eventTime
                lastUpX = x
                lastUpY = y
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelAll()
            }
        }
        return true
    }

    /**
     * Cancels all active trackers and releases pressed states without committing.
     */
    fun cancelAll() {
        for (i in 0 until MAX_TRACKERS) {
            if (trackers[i].isDown) {
                trackers[i].onCancel()
            }
            trackers[i].reset()
        }
        pointerIdToTrackerIndex.fill(-1)
        activePointerIds.fill(-1)
        activePointerCount = 0
    }
}
