package org.fossify.keyboard.helpers

import android.content.Context
import android.view.View

/**
 * Compatibility wrapper delegating to [FeedbackManager].
 */
class KeyboardFeedbackManager(context: Context) {

    private val delegate = FeedbackManager(context)

    fun vibrateIfNeeded(view: View) {
        delegate.performHaptic(view)
    }

    fun performHapticHandleMove(view: View) {
        delegate.performHapticHandleMove(view)
    }

    fun playKeypressSoundIfNeeded(code: Int) {
        delegate.performSound(code)
    }

    fun performKeypressFeedback(view: View, keyCode: Int) {
        delegate.performKeypressFeedback(view, keyCode)
    }

    fun release() {
        delegate.release()
    }
}
