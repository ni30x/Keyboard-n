package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.InputHistorySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubPhase33Test {

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

    @Test
    fun testInitialTextSnapshot_CapturedOnStartInput() {
        val initialDraft = "Draft restored"
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.notes.app"
                inputType = InputType.TYPE_CLASS_TEXT
            },
            initialFieldText = initialDraft
        )

        assertEquals(initialDraft, InputHistoryRecordingEngine.initialText)
        assertEquals(initialDraft.length, InputHistoryRecordingEngine.charCounter)
        assertEquals(initialDraft, InputHistoryRecordingEngine.getWorkingBufferForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals(initialDraft, closedSessions[0].initial_text)
        assertEquals(initialDraft, closedSessions[0].final_text)
    }

    @Test
    fun testMiddleOfTextEdit_InsertsAndDeletesAtCursor() {
        // Start empty session
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.chat.app"
                inputType = InputType.TYPE_CLASS_TEXT
            }
        )

        // Type "Hello World"
        "Hello World".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(11, InputHistoryRecordingEngine.charCounter)
        assertEquals("Hello World", InputHistoryRecordingEngine.getWorkingBufferForTest())

        // Step 0.e: Move cursor to middle: index 6 (between "Hello " and "World")
        InputHistoryRecordingEngine.onCursorMoved(6)
        assertEquals(6, InputHistoryRecordingEngine.cursorPosition)

        // Insert "Beautiful " at the cursor in the middle
        "Beautiful ".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(21, InputHistoryRecordingEngine.charCounter)
        assertEquals("Hello Beautiful World", InputHistoryRecordingEngine.getWorkingBufferForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals("Hello Beautiful World", closedSessions[0].final_text)
    }

    @Test
    fun testDriftCheck_DetectsLengthMismatchAndResyncs() {
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.test.app"
                inputType = InputType.TYPE_CLASS_TEXT
            }
        )

        "test".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(4, InputHistoryRecordingEngine.charCounter)

        // External insert of 5 chars without IME keystroke
        InputHistoryRecordingEngine.onExternalInsert(mockContext, " extra")

        assertEquals(10, InputHistoryRecordingEngine.charCounter)
        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals("test extra", closedSessions[0].final_text)
    }

    @Test
    fun testComposingText_KeepsCandidateUntilCommitted() {
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.telex.app"
                inputType = InputType.TYPE_CLASS_TEXT
            }
        )

        // Intermediate composing updates: "h", "he", "hel"
        InputHistoryRecordingEngine.onComposingUpdate("h")
        assertEquals("h", InputHistoryRecordingEngine.composingText)

        InputHistoryRecordingEngine.onComposingUpdate("he")
        assertEquals("he", InputHistoryRecordingEngine.composingText)

        InputHistoryRecordingEngine.onComposingUpdate("hel")
        assertEquals("hel", InputHistoryRecordingEngine.composingText)

        // Suggestion tap commits final word "hello "
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 0, newText = "hello ")
        InputHistoryRecordingEngine.onComposingUpdate("")

        assertEquals("", InputHistoryRecordingEngine.composingText)
        assertEquals(6, InputHistoryRecordingEngine.charCounter)

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals("hello ", closedSessions[0].final_text)
    }

    @Test
    fun testEmojiAndNonLatin_InitialTextAndEdits() {
        val initialNonLatin = "مرحبا 🎉"
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.chat.emoji"
                inputType = InputType.TYPE_CLASS_TEXT
            },
            initialFieldText = initialNonLatin
        )

        assertEquals(initialNonLatin, InputHistoryRecordingEngine.initialText)
        assertEquals(initialNonLatin.length, InputHistoryRecordingEngine.charCounter)

        // Append Hindi text " नमस्ते"
        " नमस्ते".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals(initialNonLatin, closedSessions[0].initial_text)
        assertTrue(closedSessions[0].final_text.contains("مرحبا 🎉"))
        assertTrue(closedSessions[0].final_text.contains("नमस्ते"))
    }
}
