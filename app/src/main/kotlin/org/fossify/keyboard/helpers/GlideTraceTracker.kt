package org.fossify.keyboard.helpers

import android.view.MotionEvent
import org.fossify.keyboard.views.KeyDetector
import kotlin.math.hypot

/**
 * High-performance glide trace detector and path resolution engine.
 *
 * Responsibilities:
 * 1. Detects transition from tap gesture to continuous glide gesture.
 * 2. Ingests high-frequency batched MotionEvent historical points with zero allocations.
 * 3. Resolves traversed keys with hysteresis using Phase 2 [KeyDetector].
 * 4. Delegates word output to pluggable [TraceDecoder].
 * 5. Guarantees clean cancellation on multi-touch or gesture interruption.
 */
class GlideTraceTracker(
    var decoder: TraceDecoder = SimpleKeySequenceTraceDecoder()
) {
    companion object {
        const val DEFAULT_GLIDE_THRESHOLD_DP = 20f
        const val HYSTERESIS_FACTOR = 0.30f
    }

    val traceBuffer = TraceBuffer(500)
    val resolvedKeys = ArrayList<MyKeyboard.Key>()

    var isGliding: Boolean = false
        private set

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTimeMs: Long = 0L
    private var initialKey: MyKeyboard.Key? = null

    private var lastResolvedKey: MyKeyboard.Key? = null
    private var glideThresholdPx: Float = 40f

    var onTraceCompletedListener: ((decodedText: String, keys: List<MyKeyboard.Key>) -> Unit)? = null
    var onTraceChangedListener: (() -> Unit)? = null

    /**
     * Checks if a given key is a valid starting key for glide typing (letter key).
     */
    private fun isLetterKey(key: MyKeyboard.Key?): Boolean {
        if (key == null) return false
        val label = key.label
        if (!label.isNullOrEmpty()) {
            if (label.length == 1 && label[0].isLetter()) return true
        }
        if (key.code > 0 && key.code.toChar().isLetter()) return true
        return false
    }

    /**
     * Called on ACTION_DOWN to initialize gesture tracking.
     */
    fun onDown(x: Float, y: Float, eventTime: Long, key: MyKeyboard.Key?, keyWidthPx: Float) {
        cancel()
        downX = x
        downY = y
        downTimeMs = eventTime
        initialKey = key
        glideThresholdPx = (keyWidthPx * 0.40f).coerceAtLeast(25f)
    }

    /**
     * Processes ACTION_MOVE events including batched historical motion samples.
     * Switches to glide mode once drag distance crosses threshold.
     */
    fun onMove(event: MotionEvent, keyDetector: KeyDetector?): Boolean {
        val x = event.x
        val y = event.y

        if (!isGliding) {
            // Check if start key is a valid letter key and drag threshold is crossed
            if (!isLetterKey(initialKey)) return false

            val dx = x - downX
            val dy = y - downY
            val dist = hypot(dx, dy)

            if (dist > glideThresholdPx) {
                // Switch from tap tracking to glide trace capture
                isGliding = true
                traceBuffer.clear()
                resolvedKeys.clear()

                // Seed trace buffer with down point and current point
                traceBuffer.add(downX, downY, downTimeMs)
                initialKey?.let {
                    resolvedKeys.add(it)
                    lastResolvedKey = it
                }

                // Ingest batched historical motion points
                ingestBatchedPoints(event, keyDetector)
                onTraceChangedListener?.invoke()
                return true
            }
            return false
        }

        // Already in glide mode: process high-frequency batched points
        ingestBatchedPoints(event, keyDetector)
        onTraceChangedListener?.invoke()
        return true
    }

    private fun ingestBatchedPoints(event: MotionEvent, keyDetector: KeyDetector?) {
        val historySize = event.historySize
        for (i in 0 until historySize) {
            val hx = event.getHistoricalX(i)
            val hy = event.getHistoricalY(i)
            val ht = event.getHistoricalEventTime(i)
            traceBuffer.add(hx, hy, ht)
            if (keyDetector != null) {
                resolveKeyAt(hx, hy, keyDetector)
            }
        }

        val cx = event.x
        val cy = event.y
        val ct = event.eventTime
        traceBuffer.add(cx, cy, ct)
        if (keyDetector != null) {
            resolveKeyAt(cx, cy, keyDetector)
        }
    }

    /**
     * Resolves the key under coordinate (x, y) with spatial hysteresis.
     */
    private fun resolveKeyAt(x: Float, y: Float, keyDetector: KeyDetector) {
        val keyIndex = keyDetector.detectBestKeyIndex(x, y)
        if (keyIndex == -1) return

        val candidateKey = keyDetector.keys.getOrNull(keyIndex) ?: return
        val lastKey = lastResolvedKey

        if (lastKey == null) {
            resolvedKeys.add(candidateKey)
            lastResolvedKey = candidateKey
            return
        }

        if (candidateKey.code != lastKey.code) {
            // Apply hysteresis: candidate key must be closer to touch point than last key minus hysteresis margin
            val geomCandidate = keyDetector.keyGeometries.getOrNull(keyIndex)
            val geomLast = keyDetector.keyGeometries.firstOrNull { it.key.code == lastKey.code }

            if (geomCandidate != null && geomLast != null) {
                val distCandidateSq = geomCandidate.squaredDistanceToEdge(x, y)
                val distLastSq = geomLast.squaredDistanceToEdge(x, y)
                val hysteresisMarginSq = (geomLast.width * HYSTERESIS_FACTOR) * (geomLast.width * HYSTERESIS_FACTOR)

                if (distCandidateSq < distLastSq + hysteresisMarginSq) {
                    resolvedKeys.add(candidateKey)
                    lastResolvedKey = candidateKey
                }
            } else {
                resolvedKeys.add(candidateKey)
                lastResolvedKey = candidateKey
            }
        }
    }

    /**
     * Called on ACTION_UP to complete trace capture and dispatch decoded output.
     */
    fun onUp(): Boolean {
        if (!isGliding) return false

        val keysCopy = ArrayList(resolvedKeys)
        val pointsCopy = traceBuffer.toList()
        val decodedText = decoder.decode(keysCopy, pointsCopy)

        cancel()

        if (decodedText.isNotEmpty()) {
            onTraceCompletedListener?.invoke(decodedText, keysCopy)
        }
        return true
    }

    /**
     * Cleanly cancels glide mode, clearing all points and resetting state.
     * Called on ACTION_CANCEL, multi-touch ACTION_POINTER_DOWN, or boundary exit.
     */
    fun cancel() {
        val wasGliding = isGliding
        isGliding = false
        initialKey = null
        lastResolvedKey = null
        traceBuffer.clear()
        resolvedKeys.clear()

        if (wasGliding) {
            onTraceChangedListener?.invoke()
        }
    }
}
