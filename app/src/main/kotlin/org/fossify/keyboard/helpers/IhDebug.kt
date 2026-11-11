package org.fossify.keyboard.helpers

import android.util.Log
import org.fossify.keyboard.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight debug-only instrumentation helper for Input History tracking.
 * Strictly respects privacy: NEVER logs typed text or characters.
 * All logs are guarded by BuildConfig.DEBUG and use tag "IHDBG".
 */
object IhDebug {
    private const val TAG = "IHDBG"
    private val sequenceNumber = AtomicInteger(0)

    fun log(
        event: String,
        sessionId: Long = 0L,
        charCounter: Int = 0,
        bufferLen: Int = 0,
        liveIndicesSize: Int = 0,
        deletionsSize: Int = 0,
        pkg: String = "",
        extra: String = ""
    ) {
        val seq = sequenceNumber.incrementAndGet()
        val ts = System.currentTimeMillis()
        val extraStr = if (extra.isNotEmpty()) " $extra" else ""
        val formatted = "seq=$seq ts=$ts sid=$sessionId ev=$event cc=$charCounter blen=$bufferLen live=$liveIndicesSize dels=$deletionsSize pkg=$pkg$extraStr"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, formatted)
        }
        AppLogManager.log(TAG, formatted)
    }

    fun logBypass(action: String, length: Int, extra: String = "") {
        val seq = sequenceNumber.incrementAndGet()
        val ts = System.currentTimeMillis()
        val extraStr = if (extra.isNotEmpty()) " $extra" else ""
        val formatted = "seq=$seq ts=$ts ev=$action BYPASS len=$length$extraStr"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, formatted)
        }
        AppLogManager.log(TAG, formatted)
    }

    fun logSelection(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        candStart: Int,
        candEnd: Int
    ) {
        val seq = sequenceNumber.incrementAndGet()
        val ts = System.currentTimeMillis()
        val formatted = "seq=$seq ts=$ts ev=onUpdateSelection sel=[$oldStart,$oldEnd]->[$newStart,$newEnd] cand=[$candStart,$candEnd]"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, formatted)
        }
        AppLogManager.log(TAG, formatted)
    }
}
