package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.lang.reflect.Proxy
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.InputHistorySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubPhase34Test {

    private class TestContext : ContextWrapper(null) {
        override fun getPackageManager(): PackageManager? = null
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == Context.USER_SERVICE) {
                return Proxy.newProxyInstance(
                    Class.forName("android.os.UserManager").classLoader,
                    arrayOf(Class.forName("android.os.UserManager"))
                ) { _, method, _ ->
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> true
                        else -> null
                    }
                }
            }
            return super.getSystemService(name)
        }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val mockEditor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, _ ->
                if (method.returnType.isAssignableFrom(SharedPreferences.Editor::class.java)) proxy else null
            } as SharedPreferences.Editor

            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getBoolean" -> true
                    "getStringSet" -> HashSet<String>()
                    "getString" -> ""
                    "getInt" -> 0
                    "getLong" -> 0L
                    "getFloat" -> 0f
                    "edit" -> mockEditor
                    else -> null
                }
            } as SharedPreferences
        }
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
    fun testDelayedEmptyCheck_PendingEmptyStateAndTrigger() {
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.test.app"
                inputType = InputType.TYPE_CLASS_TEXT
            }
        )

        "to be cleared".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(13, InputHistoryRecordingEngine.charCounter)

        // Select-all delete to 0
        InputHistoryRecordingEngine.onSelectionDeleted(mockContext, 13)

        // Session closes honestly as STATUS_DISCARDED on full selection delete
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_DISCARDED, closedSessions[0].status)
    }

    @Test
    fun testDelayedEmptyCheck_WordReplacementResumesActiveState() {
        InputHistoryRecordingEngine.onStartInput(
            mockContext,
            EditorInfo().apply {
                packageName = "com.replace.app"
                inputType = InputType.TYPE_CLASS_TEXT
            }
        )

        "typo".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // User replaces "typo" (4 chars) with "corrected"
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 4, newText = "corrected")

        // State remains ACTIVE, working buffer holds full history
        assertEquals(InputHistoryRecordingEngine.LiveState.ACTIVE, InputHistoryRecordingEngine.liveState)
        assertEquals(9, InputHistoryRecordingEngine.charCounter)
        assertEquals(0, closedSessions.size)

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals("typocorrected", closedSessions[0].final_text)
    }

    @Test
    fun testMultiLineEnter_InsertsNewlineWithoutSubmitting() {
        val multiLineInfo = EditorInfo().apply {
            packageName = "com.notes.app"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            fieldId = 101
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, multiLineInfo)
        "Line 1".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Enter key pressed in multi-line field
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, multiLineInfo)

        // Buffer has newline, session remains open and active
        assertEquals("Line 1\n", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(7, InputHistoryRecordingEngine.charCounter)
        assertEquals(0, closedSessions.size)

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[0].status)
    }

    @Test
    fun testSingleLineSend_MarksSubmitAndCloses() {
        val singleLineInfo = EditorInfo().apply {
            packageName = "com.chat.app"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 202
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, singleLineInfo)
        "Chat message".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Enter / Action pressed in single-line field
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, singleLineInfo)

        // Single line submit closes as STATUS_SENT when field clears
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)
        assertEquals(1, closedSessions.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_SENT, closedSessions[0].status)
    }

    @Test
    fun testSessionIdentity_KeyboardHideAndReshowResumesSession() {
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true
        val fieldInfo = EditorInfo().apply {
            packageName = "com.messaging.app"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 303
        }

        // 1. User starts typing draft
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo)
        "Unsent draft".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // 2. Keyboard hides (onFinishInput) with content in field
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        // Memory marks session paused; zero DB records saved yet
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(0, closedSessions.size)

        // 3. User re-opens keyboard in SAME field
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo, initialFieldText = "Unsent draft")

        // Session resumed!
        assertFalse(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(12, InputHistoryRecordingEngine.charCounter)
        assertEquals(0, closedSessions.size)

        // 4. User finishes message and sends
        " sent".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, fieldInfo)
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)

        // Single consolidated session saved!
        assertEquals(1, closedSessions.size)
        assertEquals("Unsent draft sent", closedSessions[0].final_text)
    }

    @Test
    fun testSessionIdentity_AppOrFieldSwitchFinalizesPreviousSession() {
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true
        val fieldA = EditorInfo().apply {
            packageName = "com.app.a"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 1
        }
        val fieldB = EditorInfo().apply {
            packageName = "com.app.b"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 2
        }

        // Start in App A
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldA)
        "App A text".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Keyboard hides
        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(0, closedSessions.size)

        // User switches to App B
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldB)

        // App A session finalized as STATUS_LEFT_FIELD
        assertEquals(1, closedSessions.size)
        assertEquals("com.app.a", closedSessions[0].app_package)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[0].status)
    }

    @Test
    fun testStep0a_ResumeCheck_FieldNowEmptyCreatesTwoSeparateSessions() {
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true
        val fieldInfo = EditorInfo().apply {
            packageName = "com.chat.step0"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 999
        }

        // 1. User types "msg one"
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo)
        "msg one".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // 2. Keyboard hides with text in field -> paused
        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(0, closedSessions.size)

        // 3. User returns to SAME field key, but field is now empty (initialFieldText = "")
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo, initialFieldText = "")

        // Old session finalized as LEFT_FIELD
        assertEquals(1, closedSessions.size)
        assertEquals("msg one", closedSessions[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[0].status)

        // 4. User types "msg two" in the new session
        "msg two".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = false
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        // 5. Total of two separate sessions!
        assertEquals(2, closedSessions.size)
        assertEquals("msg two", closedSessions[1].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_LEFT_FIELD, closedSessions[1].status)
    }

    @Test
    fun testStep0b_PersistOnPause_SavesDraftCheckpoint() {
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true
        val savedCheckpoints = ArrayList<InputHistorySession>()
        InputHistoryRecordingEngine.onCheckpointSavedForTest = { session, _ ->
            savedCheckpoints.add(session)
        }

        val fieldInfo = EditorInfo().apply {
            packageName = "com.draft.app"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 777
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo)
        "Draft text".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // When keyboard hides and session becomes PAUSED, draft is persisted immediately
        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(1, savedCheckpoints.size)
        assertEquals("Draft text", savedCheckpoints[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_DRAFT, savedCheckpoints[0].status)
    }

    @Test
    fun testStep0c_EnterDetection_ExplicitParenthesesRealisticInputTypes() {
        val singleLineInfo = EditorInfo().apply {
            packageName = "com.test.single"
            inputType = InputType.TYPE_CLASS_TEXT // 1 (single-line)
        }
        val multiLineInfo = EditorInfo().apply {
            packageName = "com.test.multi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE // 131073
        }
        val imeMultiLineInfo = EditorInfo().apply {
            packageName = "com.test.imemulti"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE // 262145
        }
        val nullTypeInfo = EditorInfo().apply {
            packageName = "com.test.nulltype"
            inputType = InputType.TYPE_NULL // 0
        }

        // 1. Single-line: Enter does not insert newline
        InputHistoryRecordingEngine.onStartInput(mockContext, singleLineInfo)
        "Text".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, singleLineInfo)
        assertEquals("Text", InputHistoryRecordingEngine.getWorkingBufferForTest())
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        // 2. Multi-line: Enter inserts newline
        InputHistoryRecordingEngine.onStartInput(mockContext, multiLineInfo)
        "Line 1".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, multiLineInfo)
        assertEquals("Line 1\n", InputHistoryRecordingEngine.getWorkingBufferForTest())
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        // 3. IME Multi-line: Enter inserts newline
        InputHistoryRecordingEngine.onStartInput(mockContext, imeMultiLineInfo)
        "Note 1".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, imeMultiLineInfo)
        assertEquals("Note 1\n", InputHistoryRecordingEngine.getWorkingBufferForTest())
        InputHistoryRecordingEngine.onFinishInput(mockContext)

        // 4. Null type: Enter does not insert newline
        InputHistoryRecordingEngine.onStartInput(mockContext, nullTypeInfo)
        "Raw".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, nullTypeInfo)
        assertEquals("Raw", InputHistoryRecordingEngine.getWorkingBufferForTest())
        InputHistoryRecordingEngine.onFinishInput(mockContext)
    }

    @Test
    fun testStep0d_DelayedEmptyCheck_CancelledWhenTextReturns() {
        InputHistoryRecordingEngine.emptyCheckDelayMs = 5000L // Ensure delay is long enough
        val fieldInfo = EditorInfo().apply {
            packageName = "com.replace.test"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 555
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo)
        "hello".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(5, InputHistoryRecordingEngine.charCounter)

        // Field drops to 0 via mock update
        val mockEmptyIc = Proxy.newProxyInstance(
            java.lang.Class.forName("android.view.inputmethod.InputConnection").classLoader,
            arrayOf(java.lang.Class.forName("android.view.inputmethod.InputConnection"))
        ) { _, method, _ ->
            when (method.name) {
                "getTextBeforeCursor", "getTextAfterCursor" -> ""
                else -> null
            }
        } as android.view.inputmethod.InputConnection

        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, mockEmptyIc)
        assertEquals(InputHistoryRecordingEngine.LiveState.PENDING_EMPTY, InputHistoryRecordingEngine.liveState)

        // Text returns before timer expires (word replacement / typing continues)
        InputHistoryRecordingEngine.onKeystroke("world")
        assertEquals(InputHistoryRecordingEngine.LiveState.ACTIVE, InputHistoryRecordingEngine.liveState)
        assertEquals(0, closedSessions.size)

        // Triggering check while ACTIVE does nothing
        InputHistoryRecordingEngine.triggerPendingEmptyCheck(mockContext)
        assertEquals(0, closedSessions.size)

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals("helloworld", closedSessions[0].final_text)
    }
}
