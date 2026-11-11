package org.fossify.keyboard.helpers

import java.util.Locale

/**
 * Pluggable interface for resolving a continuous glide trace path and ordered
 * sequence of touched keys into text output.
 */
interface TraceDecoder {
    /**
     * Decodes an ordered sequence of keys traversed during a glide gesture.
     *
     * @param keys Ordered list of keys passed through during the trace (with hysteresis applied).
     * @param tracePoints Sampled high-frequency (x, y, time) touch coordinates along the path.
     * @return Decoded text result.
     */
    fun decode(keys: List<MyKeyboard.Key>, tracePoints: List<TracePoint>): String
}

/**
 * Default trivial [TraceDecoder] implementation that emits the ordered sequence
 * of key characters directly as text without dictionary lookup or word prediction.
 */
class SimpleKeySequenceTraceDecoder : TraceDecoder {
    override fun decode(keys: List<MyKeyboard.Key>, tracePoints: List<TracePoint>): String {
        if (keys.isEmpty()) return ""
        val sb = StringBuilder()
        for (key in keys) {
            val label = key.label
            if (!label.isNullOrEmpty()) {
                if (label.length == 1 && label[0].isLetter()) {
                    sb.append(label.toString().lowercase(Locale.ROOT))
                } else if (key.code > 0 && key.code.toChar().isLetter()) {
                    sb.append(key.code.toChar().lowercaseChar())
                }
            } else if (key.code > 0 && key.code.toChar().isLetter()) {
                sb.append(key.code.toChar().lowercaseChar())
            }
        }
        return sb.toString()
    }
}
