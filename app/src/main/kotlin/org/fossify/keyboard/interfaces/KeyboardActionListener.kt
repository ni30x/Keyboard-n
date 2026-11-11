package org.fossify.keyboard.interfaces

import android.view.inputmethod.InputMethodSubtype

/**
 * Interface definition for keyboard touch and action callbacks.
 */
interface KeyboardActionListener {
    /**
     * Called when the user presses a key (immediate touch-down feedback).
     * @param primaryCode the code of the key being pressed.
     */
    fun onPress(primaryCode: Int)

    /**
     * Called when the user releases a key.
     * @param primaryCode the code of the key being released.
     */
    fun onRelease(primaryCode: Int) {}

    /**
     * Send a key code to the listener to commit it.
     * @param code the key that was pressed.
     */
    fun onKey(code: Int)

    /**
     * Called when a pointer lifts after pressing a key.
     */
    fun onActionUp()

    /**
     * Called when spacebar cursor sliding moves left.
     */
    fun moveCursorLeft()

    /**
     * Called when spacebar cursor sliding moves right.
     */
    fun moveCursorRight()

    /**
     * Sends a sequence of characters to the listener.
     * @param text the string to be inserted.
     */
    fun onText(text: String)

    /**
     * Called to force the KeyboardView to reload the keyboard.
     */
    fun reloadKeyboard()

    /**
     * Called when input method is changed in-app.
     */
    fun changeInputMethod(id: String, subtype: InputMethodSubtype)

    /**
     * Checks whether the current text input field is empty.
     */
    fun isInputFieldEmpty(): Boolean = true

    /**
     * Called when touch event is cancelled without committing.
     */
    fun onCancel() {}
}
