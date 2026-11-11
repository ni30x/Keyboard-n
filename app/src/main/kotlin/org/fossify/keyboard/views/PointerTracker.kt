package org.fossify.keyboard.views

import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import org.fossify.keyboard.helpers.GlideTraceTracker
import org.fossify.keyboard.helpers.MyKeyboard

/**
 * Delegate interface providing callback hooks from [PointerTracker] back to the hosting view.
 */
interface PointerTrackerDelegate {
    fun getKeyAt(x: Int, y: Int): Int
    fun getKey(keyIndex: Int): MyKeyboard.Key?
    fun onKeyPressFeedback(key: MyKeyboard.Key)
    fun onKeyCommit(key: MyKeyboard.Key, touchX: Float = 0f, touchY: Float = 0f)
    fun onKeyLongPress(key: MyKeyboard.Key, x: Float, y: Float): Boolean = false
    fun onKeyRepeat(key: MyKeyboard.Key)
    fun onSpaceSlide(dx: Float)
    fun setKeyPressed(keyIndex: Int, pressed: Boolean)
    fun showPreview(keyIndex: Int)
    fun isGlideTypingEnabled(): Boolean = false
    fun getHysteresisFraction(): Float = 0.25f
    fun getPhantomMoveFraction(): Float = 0.25f
    fun getTouchNoiseTimeThresholdMs(): Long = 40L
    fun getTouchNoiseDistanceThresholdPx(): Float = 25f
    fun getLongPressTimeoutMs(): Long = 300L
    val glideTraceTracker: GlideTraceTracker? get() = null
    fun getKeyDetector(): KeyDetector? = null
}

/**
 * Preallocated, per-pointer tracker object managing the lifecycle, timers, hysteresis,
 * and gestures for a single touch pointer.
 */
class PointerTracker(
    val trackerId: Int,
    private val delegate: PointerTrackerDelegate
) {
    companion object {
        const val NOT_A_KEY = -1
        const val REPEAT_START_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        val LONGPRESS_TIMEOUT_MS = ViewConfiguration.getLongPressTimeout().toLong()
    }

    var pointerId: Int = -1
        private set

    var isDown: Boolean = false
        private set

    var currentKeyIndex: Int = NOT_A_KEY
        private set

    var downKeyIndex: Int = NOT_A_KEY
        private set

    var downX: Float = 0f
        private set

    var downY: Float = 0f
        private set

    var downTime: Long = 0L
        private set

    var lastX: Float = 0f
        private set

    var lastY: Float = 0f
        private set

    var lastTime: Long = 0L
        private set

    var hasLongPressed: Boolean = false
        private set

    var isRepeating: Boolean = false
        private set

    var isSlidOff: Boolean = false
        private set

    var hasVirtualReleased: Boolean = false
        private set

    var isSlidingSpace: Boolean = false
        private set

    private val mHandler = Handler(Looper.getMainLooper())

    private val mLongPressRunnable = Runnable {
        if (!isDown || hasLongPressed || hasVirtualReleased || isSlidOff) return@Runnable
        val key = delegate.getKey(currentKeyIndex)
        if (key != null) {
            val handled = delegate.onKeyLongPress(key, lastX, lastY)
            if (handled) {
                hasLongPressed = true
                cancelTimers()
            }
        }
    }

    private val mRepeatRunnable = object : Runnable {
        override fun run() {
            if (!isDown || hasVirtualReleased || isSlidOff) return
            val key = delegate.getKey(currentKeyIndex)
            if (key != null && key.repeatable) {
                delegate.onKeyRepeat(key)
                mHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Binds this tracker to a specific MotionEvent pointerId.
     */
    fun bind(pointerId: Int) {
        this.pointerId = pointerId
    }

    /**
     * Handles ACTION_DOWN or ACTION_POINTER_DOWN for this pointer.
     */
    fun onDown(x: Float, y: Float, eventTime: Long) {
        cancelTimers()

        isDown = true
        downX = x
        downY = y
        downTime = eventTime
        lastX = x
        lastY = y
        lastTime = eventTime
        hasLongPressed = false
        hasVirtualReleased = false
        isSlidOff = false
        isRepeating = false
        isSlidingSpace = false

        val keyIdx = delegate.getKeyAt(x.toInt(), y.toInt())
        downKeyIndex = keyIdx
        currentKeyIndex = keyIdx

        if (keyIdx != NOT_A_KEY) {
            val key = delegate.getKey(keyIdx)
            if (key != null) {
                // Immediate visual & press feedback (callbacks only, no heavy work)
                delegate.setKeyPressed(keyIdx, true)
                delegate.showPreview(keyIdx)
                delegate.onKeyPressFeedback(key)

                val keyWidth = key.width.toFloat()
                delegate.glideTraceTracker?.onDown(x, y, eventTime, key, keyWidth)

                if (key.repeatable && key.code != MyKeyboard.KEYCODE_SPACE) {
                    isRepeating = true
                    // First commit happens immediately on repeatable keys (delete, arrows)
                    if (key.code != MyKeyboard.KEYCODE_SPACE) {
                        delegate.onKeyCommit(key)
                        mHandler.postDelayed(mRepeatRunnable, REPEAT_START_DELAY_MS)
                    }
                } else {
                    // Check if key supports long-press alternates
                    if (key.popupResId != 0 || key.popupCharacters != null ||
                        key.code == MyKeyboard.KEYCODE_SPACE || key.code == MyKeyboard.KEYCODE_EMOJI_OR_LANGUAGE
                    ) {
                        mHandler.postDelayed(mLongPressRunnable, delegate.getLongPressTimeoutMs())
                    }
                }
            }
        }
    }

    /**
     * Handles ACTION_MOVE sample for this pointer.
     */
    fun onMove(x: Float, y: Float, eventTime: Long, event: android.view.MotionEvent? = null) {
        if (!isDown || hasVirtualReleased) return

        if (event != null && delegate.isGlideTypingEnabled()) {
            val tracker = delegate.glideTraceTracker
            if (tracker != null) {
                val isGlideActive = tracker.onMove(event, delegate.getKeyDetector())
                if (isGlideActive) {
                    cancelTimers()
                    delegate.showPreview(NOT_A_KEY)
                    if (currentKeyIndex != NOT_A_KEY) {
                        delegate.setKeyPressed(currentKeyIndex, false)
                        currentKeyIndex = NOT_A_KEY
                    }
                    lastX = x
                    lastY = y
                    lastTime = eventTime
                    return
                }
            }
        }

        val currentKey = delegate.getKey(currentKeyIndex)

        // 1. Phantom-move guard: if a single MOVE jumps more than ~25% of key width, treat as UP-then-DOWN
        if (currentKey != null) {
            val dx = x - lastX
            val dy = y - lastY
            val distSq = dx * dx + dy * dy
            val phantomThreshold = currentKey.width * delegate.getPhantomMoveFraction()
            if (distSq > phantomThreshold * phantomThreshold) {
                // Process virtual UP at previous position, then DOWN at new position
                onUp(lastX, lastY, eventTime, isPhantom = true)
                onDown(x, y, eventTime)
                return
            }
        }

        // 2. Spacebar cursor sliding (requires moving past scaled touch slop threshold)
        if (currentKey != null && currentKey.code == MyKeyboard.KEYCODE_SPACE) {
            val totalDx = x - downX
            val touchSlop = 48f // Robust touch slop threshold (~48px) so finger taps never trigger cursor sliding
            if (kotlin.math.abs(totalDx) > touchSlop) {
                if (!isSlidingSpace) {
                    isSlidingSpace = true
                    cancelTimers()
                }
                val diffX = x - lastX
                delegate.onSpaceSlide(diffX)
            }
            lastX = x
            lastY = y
            lastTime = eventTime
            return
        }

        // 3. Hysteresis calculation: switch key only after moving past hysteresis margin
        if (currentKey != null && !isSlidOff) {
            val margin = currentKey.width * delegate.getHysteresisFraction()
            val left = currentKey.x - margin
            val right = currentKey.x + currentKey.width + margin
            val top = currentKey.y - margin
            val bottom = currentKey.y + currentKey.height + margin

            val isInsideHysteresis = x >= left && x <= right && y >= top && y <= bottom
            if (isInsideHysteresis) {
                // Finger remains within hysteresis boundary of current key
                lastX = x
                lastY = y
                lastTime = eventTime
                return
            }

            // Moved out of current key's hysteresis boundary
            cancelTimers()

            if (!delegate.isGlideTypingEnabled()) {
                // Slide off key cancels tap when glide typing is disabled
                isSlidOff = true
                delegate.setKeyPressed(currentKeyIndex, false)
                delegate.showPreview(NOT_A_KEY)
                currentKeyIndex = NOT_A_KEY
                lastX = x
                lastY = y
                lastTime = eventTime
                return
            } else {
                // In glide mode, switch to candidate key under finger
                val newKeyIdx = delegate.getKeyAt(x.toInt(), y.toInt())
                if (newKeyIdx != currentKeyIndex) {
                    delegate.setKeyPressed(currentKeyIndex, false)
                    currentKeyIndex = newKeyIdx
                    if (newKeyIdx != NOT_A_KEY) {
                        delegate.setKeyPressed(newKeyIdx, true)
                        delegate.showPreview(newKeyIdx)
                    }
                }
            }
        }

        lastX = x
        lastY = y
        lastTime = eventTime
    }

    /**
     * Handles ACTION_UP or ACTION_POINTER_UP for this pointer.
     */
    fun onUp(x: Float, y: Float, eventTime: Long, isPhantom: Boolean = false) {
        cancelTimers()

        if (!isDown) return

        val tracker = delegate.glideTraceTracker
        if (tracker != null && tracker.isGliding) {
            if (currentKeyIndex != NOT_A_KEY) {
                delegate.setKeyPressed(currentKeyIndex, false)
                currentKeyIndex = NOT_A_KEY
            }
            delegate.showPreview(NOT_A_KEY)
            tracker.onUp()
            isDown = false
            return
        }

        isDown = false

        // If sliding spacebar for cursor navigation, do not commit space key
        if (isSlidingSpace) {
            if (currentKeyIndex != NOT_A_KEY) {
                delegate.setKeyPressed(currentKeyIndex, false)
            }
            delegate.showPreview(NOT_A_KEY)
            currentKeyIndex = NOT_A_KEY
            return
        }

        // If previously virtual-released by rollover, skip committing again
        if (hasVirtualReleased) {
            if (currentKeyIndex != NOT_A_KEY) {
                delegate.setKeyPressed(currentKeyIndex, false)
            }
            delegate.showPreview(NOT_A_KEY)
            currentKeyIndex = NOT_A_KEY
            return
        }

        // If tap was cancelled by sliding off key, do not commit
        if (isSlidOff) {
            delegate.showPreview(NOT_A_KEY)
            currentKeyIndex = NOT_A_KEY
            return
        }

        // If long press fired, alternates were already shown/handled
        if (hasLongPressed) {
            if (currentKeyIndex != NOT_A_KEY) {
                delegate.setKeyPressed(currentKeyIndex, false)
            }
            delegate.showPreview(NOT_A_KEY)
            currentKeyIndex = NOT_A_KEY
            return
        }

        // If repeatable key, repeat timer already handled commits
        if (isRepeating) {
            if (currentKeyIndex != NOT_A_KEY) {
                delegate.setKeyPressed(currentKeyIndex, false)
            }
            delegate.showPreview(NOT_A_KEY)
            currentKeyIndex = NOT_A_KEY
            return
        }

        // Normal key commit on UP
        val key = delegate.getKey(currentKeyIndex)
        if (key != null && currentKeyIndex != NOT_A_KEY) {
            delegate.setKeyPressed(currentKeyIndex, false)
            delegate.showPreview(NOT_A_KEY)
            delegate.onKeyCommit(key, downX, downY)
        }
        currentKeyIndex = NOT_A_KEY
    }

    /**
     * Executes a virtual release on this pointer (triggered by rollover when a newer pointer lifts).
     */
    fun onVirtualRelease(eventTime: Long) {
        cancelTimers()

        if (!isDown || hasVirtualReleased || isSlidOff || hasLongPressed || isSlidingSpace) return
        hasVirtualReleased = true

        if (!isRepeating && currentKeyIndex != NOT_A_KEY) {
            val key = delegate.getKey(currentKeyIndex)
            if (key != null) {
                delegate.setKeyPressed(currentKeyIndex, false)
                delegate.onKeyCommit(key, downX, downY)
            }
        }
        delegate.showPreview(NOT_A_KEY)
    }

    /**
     * Handles ACTION_CANCEL: releases pressed state without committing any key.
     */
    fun onCancel() {
        cancelTimers()
        delegate.glideTraceTracker?.cancel()

        if (currentKeyIndex != NOT_A_KEY) {
            delegate.setKeyPressed(currentKeyIndex, false)
        }
        delegate.showPreview(NOT_A_KEY)

        isDown = false
        hasVirtualReleased = false
        hasLongPressed = false
        isSlidOff = false
        isRepeating = false
        isSlidingSpace = false
        currentKeyIndex = NOT_A_KEY
        downKeyIndex = NOT_A_KEY
    }

    /**
     * Resets the tracker for reuse.
     */
    fun reset() {
        cancelTimers()
        pointerId = -1
        isDown = false
        hasVirtualReleased = false
        hasLongPressed = false
        isSlidOff = false
        isRepeating = false
        isSlidingSpace = false
        currentKeyIndex = NOT_A_KEY
        downKeyIndex = NOT_A_KEY
        downX = 0f
        downY = 0f
        downTime = 0L
        lastX = 0f
        lastY = 0f
        lastTime = 0L
    }

    private fun cancelTimers() {
        mHandler.removeCallbacks(mLongPressRunnable)
        mHandler.removeCallbacks(mRepeatRunnable)
    }
}
