package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.InputHistorySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputHistoryMatrixTest {

    private class TestContext : ContextWrapper(null) {
        override fun getPackageManager(): PackageManager? = null
        override fun getApplicationContext(): Context = this
    }

    private lateinit var mockContext: Context
    private val closedSessions = ArrayList<InputHistorySession>()

    @Before
    fun setUp() {
        InputHistoryRecordingEngine.resetForTests()
        closedSessions.clear()
        InputHistoryRecordingEngine.onSessionClosedForTest = { session, _ ->
            closedSessions.add(session)
        }
        mockContext = TestContext()
    }

    private fun createEditorInfo(pkg: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditorInfo {
        return EditorInfo().apply {
            packageName = pkg
            this.inputType = inputType
        }
    }

    @Test
    fun testT1_BasicCaptureAndSendPath() {
        // T1: Type "hello world" with plain keys, press IME Enter/Send
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.chat.app"))
        "hello world".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        assertEquals(11, InputHistoryRecordingEngine.charCounter)
        assertEquals("hello world", InputHistoryRecordingEngine.getWorkingBufferForTest())

        // Press IME Enter / Send
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null)

        // In field-cleared update (after action)
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)
        // With inputConnection null (simulating field cleared to 0)
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_SENT, closedSessions[0].status)
        assertEquals("hello world", closedSessions[0].final_text)
    }

    @Test
    fun testT2_AppOwnSendButton_FinalizationViaOnFinishInput() {
        // T2: Type short message, app send button tapped -> onFinishInput called
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.chat.app"))
        "chat message".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Allowed Repair 1: onFinishInput saves active buffer before wipe as honest STATUS_LEFT_FIELD
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        assertEquals(1, closedSessions.size)
        assertEquals("chat message", closedSessions[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[0].status)
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)
    }

    @Test
    fun testT3_SelectAllAndDelete_NowProducesDiscarded() {
        // T3: Type, then select all and delete
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.notes.app"))
        "to be deleted".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Selection deleted
        InputHistoryRecordingEngine.onSelectionDeleted(mockContext, 13)
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)

        assertEquals(1, closedSessions.size)
        // Honest status in 3.1: select-all delete produces STATUS_DISCARDED, never STATUS_SENT
        assertEquals(InputHistoryRecordingEngine.STATUS_DISCARDED, closedSessions[0].status)
        assertEquals("to be deleted", closedSessions[0].final_text)
    }

    @Test
    fun testClearedUnknown_WhenFieldClearedWithoutActionOrSelectionDelete() {
        // Sudden field drop to 0 without submit and without selection deletion
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.notes.app"))
        "external clear".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN, closedSessions[0].status)
    }

    @Test
    fun testT6_SwipeTypingResult() {
        // T6: Swipe-type a word
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.test.app"))
        InputHistoryRecordingEngine.onKeystroke("swiped")

        assertEquals(6, InputHistoryRecordingEngine.charCounter)
        assertEquals("swiped", InputHistoryRecordingEngine.getWorkingBufferForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals("swiped", closedSessions[0].final_text)
    }

    @Test
    fun testT8_EmojiAndMultiUnitCharacters() {
        // T8: Insert emoji
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.test.app"))
        val emoji = "😊" // surrogate pair (2 chars)
        InputHistoryRecordingEngine.onKeystroke(emoji)

        assertEquals(2, InputHistoryRecordingEngine.charCounter)
        assertEquals("😊", InputHistoryRecordingEngine.getWorkingBufferForTest())

        // Allowed Repair 3: onWordDeleted with count = 2 deletes both units
        InputHistoryRecordingEngine.onWordDeleted(mockContext, 2)
        assertEquals(0, InputHistoryRecordingEngine.charCounter)
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_DISCARDED, closedSessions[0].status)
    }

    @Test
    fun testT10_ReplaceOnlyWord_FalseZero() {
        // T10: Replace only word: type "cat", backspace 3 times down to 0, then type "dog"
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.test.app"))
        "cat".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Deleting to zero triggers DISCARDED
        repeat(3) { InputHistoryRecordingEngine.onBackspace(mockContext) }
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_DISCARDED, closedSessions[0].status)
        assertEquals("cat", closedSessions[0].final_text)

        // Typing "dog" continues in new session
        "dog".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(2, closedSessions.size)
        assertEquals("dog", closedSessions[1].final_text)
    }

    @Test
    fun testT13_TwoAppsSeparation() {
        // T13: Two apps test
        // 1. In app A, type "AAA 111", do not send
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.app.a"))
        "AAA 111".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // 2. Switch to app B: onStartInput with com.app.b detects app switch, saves A as LEFT_FIELD, opens B
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.app.b"))
        assertEquals(1, closedSessions.size)
        assertEquals("com.app.a", closedSessions[0].app_package)
        assertEquals("AAA 111", closedSessions[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[0].status)

        // Type "BBB 222" and send in B
        "BBB 222".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)
        assertEquals(2, closedSessions.size)
        assertEquals("com.app.b", closedSessions[1].app_package)
        assertEquals("BBB 222", closedSessions[1].final_text)

        // 3. Switch back to A, add " 333", send it
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.app.a"))
        " 333".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)
        assertEquals(3, closedSessions.size)
        assertEquals("com.app.a", closedSessions[2].app_package)
        assertEquals(" 333", closedSessions[2].final_text)
    }

    @Test
    fun testT14_PasswordFieldPrivacyGate() {
        // T14: Password field
        val passwordEditorInfo = createEditorInfo("com.login.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        InputHistoryRecordingEngine.onStartInput(mockContext, passwordEditorInfo)

        assertFalse("Recording must not be active for password fields", InputHistoryRecordingEngine.isRecordingActive)
        assertNull("Working buffer must be null for password fields", InputHistoryRecordingEngine.getWorkingBufferForTest())

        "secret123".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        assertEquals(0, closedSessions.size)
    }

    @Test
    fun testT16_LongTextBufferIntegrity() {
        // T16: Type 600 characters
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.doc.app"))
        val longString = "A".repeat(600)
        longString.forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        assertEquals(600, InputHistoryRecordingEngine.charCounter)
        assertEquals(600, InputHistoryRecordingEngine.getWorkingBufferForTest()?.length)

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals(600, closedSessions[0].final_text.length)
    }

    @Test
    fun testT17_MasterSwitchOffMidSession() {
        // T17: Toggle master switch off mid-session
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.test.app"))
        "before toggle".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(13, InputHistoryRecordingEngine.charCounter)

        // Mid-session toggle off stops recording
        InputHistoryRecordingEngine.stopRecording()
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)
        assertNull(InputHistoryRecordingEngine.getWorkingBufferForTest())

        // Keystroke after stop is ignored
        InputHistoryRecordingEngine.onKeystroke('x')
        assertEquals(0, InputHistoryRecordingEngine.charCounter)
    }

    @Test
    fun testT18_ExclusionList() {
        // T18: Add app to exclusion list
        InputHistoryRecordingEngine.testBlockedPackages = setOf("com.secret.bank")
        val blockedEditorInfo = createEditorInfo("com.secret.bank")
        InputHistoryRecordingEngine.onStartInput(mockContext, blockedEditorInfo)

        assertFalse(InputHistoryRecordingEngine.isRecordingActive)
        assertNull(InputHistoryRecordingEngine.getWorkingBufferForTest())

        "account details".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        assertEquals(0, closedSessions.size)
    }

    @Test
    fun testD3_DismissKeyboardWithBackAndReopenInSameField() {
        // D3: Type "hello", dismiss keyboard with back button (onFinishInputView -> onFinishInput)
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.chat.app"))
        "hello".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Keyboard dismissed
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        // Verifies exactly how many sessions result and what text each holds in Phase 3.1 state
        assertEquals(1, closedSessions.size)
        assertEquals("hello", closedSessions[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[0].status)

        // Reopen in same field, type " world", and send
        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.chat.app"))
        " world".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null)
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)

        assertEquals(2, closedSessions.size)
        assertEquals(" world", closedSessions[1].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_SENT, closedSessions[1].status)
    }

    @Test
    fun testD5_BackspaceToZeroByHand() {
        // D5: Type "hello" and backspace to zero by hand
        var capturedDeletions: List<InputHistoryRecordingEngine.PendingDeletion>? = null
        InputHistoryRecordingEngine.onSessionClosedForTest = { session, deletions ->
            closedSessions.add(session)
            capturedDeletions = deletions
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, createEditorInfo("com.chat.app"))
        "hello".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        repeat(5) {
            InputHistoryRecordingEngine.onBackspace(mockContext)
        }

        assertEquals(1, closedSessions.size)
        assertEquals("hello", closedSessions[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_DISCARDED, closedSessions[0].status)
        assertNotNull(capturedDeletions)
        assertEquals(5, capturedDeletions?.size)
    }
}
