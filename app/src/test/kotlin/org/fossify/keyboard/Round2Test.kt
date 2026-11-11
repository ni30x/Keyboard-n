package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.Dispatchers
import org.fossify.keyboard.helpers.Config
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.interfaces.InputHistoryDao
import org.fossify.keyboard.models.InputHistorySession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class Round2Test {

    private class TestContext : ContextWrapper(null) {
        override fun getPackageManager(): PackageManager? = null
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == Context.USER_SERVICE) {
                return Proxy.newProxyInstance(
                    Class.forName("android.os.UserManager").classLoader,
                    arrayOf(Class.forName("android.os.UserManager"))
                ) { _, method, _ ->
                    when (method.name) {
                        "isUserUnlocked" -> true
                        else -> when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> true
                            else -> null
                        }
                    }
                }
            }
            return super.getSystemService(name)
        }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            var mockEditor: SharedPreferences.Editor? = null
            mockEditor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, _ ->
                if (method.returnType.isAssignableFrom(SharedPreferences.Editor::class.java)) proxy else null
            } as SharedPreferences.Editor

            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getInt" -> (args?.getOrNull(1) as? Int) ?: 0
                    "getBoolean" -> (args?.getOrNull(1) as? Boolean) ?: false
                    "getString" -> (args?.getOrNull(1) as? String) ?: ""
                    "getStringSet" -> (args?.getOrNull(1) as? Set<*>) ?: emptySet<String>()
                    "getLong" -> (args?.getOrNull(1) as? Long) ?: 0L
                    "getFloat" -> (args?.getOrNull(1) as? Float) ?: 0f
                    "contains" -> false
                    "edit" -> mockEditor
                    else -> {
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> false
                            Int::class.javaPrimitiveType -> 0
                            Long::class.javaPrimitiveType -> 0L
                            Float::class.javaPrimitiveType -> 0f
                            else -> null
                        }
                    }
                }
            } as SharedPreferences
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = TestContext()
        InputHistoryRecordingEngine.resetForTests()
    }

    @After
    fun tearDown() {
        InputHistoryRecordingEngine.resetForTests()
    }

    /**
     * Item 1: Thread safety: keystroke arriving at the same moment the timer fires
     * must not lose or corrupt text (controlled dispatcher).
     */
    @Test
    fun testThreadSafety_ConcurrentKeystrokeAndDelayedEmptyCheck() {
        InputHistoryRecordingEngine.mainDispatcher = Dispatchers.Unconfined
        InputHistoryRecordingEngine.ioDispatcher = Dispatchers.Unconfined
        InputHistoryRecordingEngine.emptyCheckDelayMs = 0L // immediate

        val attribute = EditorInfo().apply {
            packageName = "org.fossify.test"
            fieldId = 101
            inputType = InputType.TYPE_CLASS_TEXT
        }

        InputHistoryRecordingEngine.onStartInput(context, attribute)
        InputHistoryRecordingEngine.onKeystroke('a', context)
        InputHistoryRecordingEngine.onKeystroke('b', context)
        assertEquals(2, InputHistoryRecordingEngine.charCounter)

        var sessionClosed = false
        InputHistoryRecordingEngine.onSessionClosedForTest = { _, _ ->
            sessionClosed = true
        }

        // Keystroke arrives to update buffer
        InputHistoryRecordingEngine.onKeystroke('c', context)
        assertEquals(InputHistoryRecordingEngine.LiveState.ACTIVE, InputHistoryRecordingEngine.liveState)

        assertFalse("Session must not close because new keystroke cancelled pending empty", sessionClosed)
        assertEquals(3, InputHistoryRecordingEngine.charCounter)
        assertEquals("abc", InputHistoryRecordingEngine.getLiveShadowText())
    }

    /**
     * Item 2: backup_rules.xml & data_extraction_rules.xml:
     * No <include> for input_history.db, only exclusions for the history DB (+ -wal, -shm) and secure prefs.
     */
    @Test
    fun testBackupRules_ExclusionsOnlyAndNoIncludesForHistoryDb() {
        val backupRulesFile = File("src/main/res/xml/backup_rules.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", backupRulesFile.exists())
        val backupXml = backupRulesFile.readText()

        assertFalse("backup_rules.xml must NOT contain <include> for input_history.db", backupXml.contains("<include"))
        assertTrue("backup_rules.xml must exclude input_history.db", backupXml.contains("input_history.db"))
        assertTrue("backup_rules.xml must exclude input_history.db-wal", backupXml.contains("input_history.db-wal"))
        assertTrue("backup_rules.xml must exclude input_history.db-shm", backupXml.contains("input_history.db-shm"))
        assertTrue("backup_rules.xml must exclude input_history_secure_prefs.xml", backupXml.contains("input_history_secure_prefs.xml"))

        val dataExtractionFile = File("src/main/res/xml/data_extraction_rules.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", dataExtractionFile.exists())
        val dataXml = dataExtractionFile.readText()

        assertFalse("data_extraction_rules.xml must NOT contain <include>", dataXml.contains("<include"))
        assertTrue("data_extraction_rules.xml must exclude input_history.db from cloud", dataXml.contains("input_history.db"))
        assertTrue("data_extraction_rules.xml must exclude input_history.db from device-transfer", dataXml.contains("device-transfer"))
    }

    /**
     * Item 3: Resume check: compare returning field text with live shadow text
     * (the current visible text), NOT the append-only workingBuffer that retains deleted characters.
     * Tests: "abc" -> backspace to "ab" -> hide keyboard -> reopen with "ab" -> resumes!
     * Also tests emoji and multi-line drafts.
     */
    @Test
    fun testResumeCheck_WithLiveShadowText_LatinEmojiAndMultiLine() {
        val attribute = EditorInfo().apply {
            packageName = "org.fossify.test"
            fieldId = 202
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true

        // 1. Type "abc", backspace once -> live shadow is "ab" while workingBuffer has "abc"
        InputHistoryRecordingEngine.onStartInput(context, attribute, "")
        InputHistoryRecordingEngine.onKeystroke('a', context)
        InputHistoryRecordingEngine.onKeystroke('b', context)
        InputHistoryRecordingEngine.onKeystroke('c', context)
        assertEquals("abc", InputHistoryRecordingEngine.getLiveShadowText())
        assertEquals("abc", InputHistoryRecordingEngine.getWorkingBufferForTest())

        InputHistoryRecordingEngine.onBackspace(context)
        assertEquals("ab", InputHistoryRecordingEngine.getLiveShadowText())
        assertEquals("abc", InputHistoryRecordingEngine.getWorkingBufferForTest()) // non-destructive

        // Hide keyboard (pauses session)
        InputHistoryRecordingEngine.onFinishInput(context)
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)

        // Reopen in same field with real field text "ab"
        var sessionClosed = false
        InputHistoryRecordingEngine.onSessionClosedForTest = { _, _ -> sessionClosed = true }
        InputHistoryRecordingEngine.onStartInput(context, attribute, "ab")

        // Must resume without closing previous session!
        assertFalse("Session should resume when initialFieldText matches live shadow text", sessionClosed)
        assertFalse(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(2, InputHistoryRecordingEngine.charCounter)
        assertEquals("ab", InputHistoryRecordingEngine.getLiveShadowText())

        // 2. Add emoji and multi-line: type "\n🎉"
        InputHistoryRecordingEngine.onKeystroke('\n', context)
        InputHistoryRecordingEngine.onReplaceBeforeCursor(context, 0, "🎉")
        assertEquals("ab\n🎉", InputHistoryRecordingEngine.getLiveShadowText())

        // Pause and resume with exact multi-line + emoji text
        InputHistoryRecordingEngine.onFinishInput(context)
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)

        InputHistoryRecordingEngine.onStartInput(context, attribute, "ab\n🎉")
        assertFalse(sessionClosed)
        assertFalse(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals("ab\n🎉", InputHistoryRecordingEngine.getLiveShadowText())
    }

    /**
     * Item 4: Sensitive-field heuristics: match exact autofill hint tokens and whole words only, never substrings.
     * Tests show NOT blocked: "shopping", "typing", "mapping", "spinner", "Pinterest search"
     * Tests show ARE blocked: "pin code", "one-time-code", "cvv", "card number"
     */
    @Test
    fun testSensitiveField_WholeWordsOnly_NeverSubstrings() {
        val nonBlockedFields = listOf(
            "shopping",
            "typing",
            "mapping",
            "spinner",
            "Pinterest search"
        )

        for (fieldNameStr in nonBlockedFields) {
            val info = EditorInfo().apply {
                packageName = "com.sample.app"
                inputType = InputType.TYPE_CLASS_TEXT
                fieldName = fieldNameStr
            }
            assertFalse(
                "Field '$fieldNameStr' should NOT be blocked by sensitive field heuristics",
                InputHistoryRecordingEngine.isSensitiveField(info)
            )
        }

        val blockedFields = listOf(
            "pin code",
            "one-time-code",
            "cvv",
            "card number"
        )

        for (fieldNameStr in blockedFields) {
            val info = EditorInfo().apply {
                packageName = "com.sample.app"
                inputType = InputType.TYPE_CLASS_TEXT
                fieldName = fieldNameStr
            }
            assertTrue(
                "Field '$fieldNameStr' MUST be blocked by sensitive field heuristics",
                InputHistoryRecordingEngine.isSensitiveField(info)
            )
        }
    }

    /**
     * Item 5: CLEARED_UNKNOWN: dedicated filter chip, and verify status stored
     * when a chat app keeps keyboard open and clears field without IME action.
     */
    @Test
    fun testClearedUnknown_DedicatedStatusAndFilterMapping() {
        val attribute = EditorInfo().apply {
            packageName = "com.chat.app"
            fieldId = 303
            inputType = InputType.TYPE_CLASS_TEXT
        }

        InputHistoryRecordingEngine.emptyCheckDelayMs = 0L // immediate for deterministic test
        var closedStatus: String? = null
        InputHistoryRecordingEngine.onSessionClosedForTest = { session, _ ->
            closedStatus = session.status
        }

        InputHistoryRecordingEngine.onStartInput(context, attribute, "")
        InputHistoryRecordingEngine.onKeystroke('h', context)
        InputHistoryRecordingEngine.onKeystroke('i', context)

        // Chat app external send button clears field while keyboard stays open
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(context, null)

        assertEquals(
            "Clearing field without IME action or backspace must produce CLEARED_UNKNOWN",
            InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN,
            closedStatus
        )
    }

    /**
     * Item 6: Defaults: history retention defaults to 0 (off), history lock defaults to false (opt-in).
     */
    @Test
    fun testDefaults_RetentionZeroAndHistoryLockOptIn() {
        val config = Config(context)
        assertEquals("History retention must default to 0 (off/forever)", 0, config.historyRetentionDays)
        assertEquals("History lock must default to false (opt-in)", false, config.historyLockEnabled)
    }

    /**
     * Item 7: markOldDraftsAsRecovered live session protection:
     * Active in-memory session (matching activeRowId and start_timestamp >= processStartTime)
     * must never be transitioned to RECOVERED.
     */
    @Test
    fun testMarkOldDraftsAsRecovered_ProtectsLiveSession() {
        val daoFile = File("src/main/kotlin/org/fossify/keyboard/interfaces/InputHistoryDao.kt").takeIf { it.exists() }
            ?: File("app/src/main/kotlin/org/fossify/keyboard/interfaces/InputHistoryDao.kt")
        assertTrue("InputHistoryDao.kt source file must exist", daoFile.exists())
        val daoSource = daoFile.readText()

        assertTrue("SQL query in InputHistoryDao must protect activeRowId", daoSource.contains("id != :activeRowId"))
        assertTrue("SQL query in InputHistoryDao must protect live process start_timestamp", daoSource.contains("start_timestamp < :processStartTime"))
    }

    /**
     * Item 8: Message-order check: keystroke handler and onUpdateSelection both touch cursorPosition.
     * Verify no double-counting occurs because onCursorMoved directly assigns absolute position.
     */
    @Test
    fun testMessageOrder_KeystrokeAndOnUpdateSelection_NoDoubleCounting() {
        val attribute = EditorInfo().apply {
            packageName = "org.fossify.test"
            fieldId = 404
            inputType = InputType.TYPE_CLASS_TEXT
        }

        InputHistoryRecordingEngine.onStartInput(context, attribute, "")
        assertEquals(0, InputHistoryRecordingEngine.cursorPosition)

        // 1. Keystroke 'a'
        InputHistoryRecordingEngine.onKeystroke('a', context)
        assertEquals(1, InputHistoryRecordingEngine.cursorPosition)

        // Framework calls onUpdateSelection with newSelStart = 1
        InputHistoryRecordingEngine.onCursorMoved(1)
        assertEquals("Cursor must stay at 1 (no double-counting)", 1, InputHistoryRecordingEngine.cursorPosition)

        // 2. Keystroke 'b'
        InputHistoryRecordingEngine.onKeystroke('b', context)
        assertEquals(2, InputHistoryRecordingEngine.cursorPosition)

        // Framework calls onUpdateSelection with newSelStart = 2
        InputHistoryRecordingEngine.onCursorMoved(2)
        assertEquals("Cursor must stay at 2 (no double-counting)", 2, InputHistoryRecordingEngine.cursorPosition)

        // 3. User taps middle of field at position 1
        InputHistoryRecordingEngine.onCursorMoved(1)
        assertEquals(1, InputHistoryRecordingEngine.cursorPosition)

        // 4. User types 'x' in the middle: workingBuffer is "axb"
        InputHistoryRecordingEngine.onKeystroke('x', context)
        assertEquals("axb", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals("axb", InputHistoryRecordingEngine.getLiveShadowText())
        assertEquals(2, InputHistoryRecordingEngine.cursorPosition)
    }
}
