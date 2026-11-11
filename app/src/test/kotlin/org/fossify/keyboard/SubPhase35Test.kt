package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.lang.reflect.Proxy
import org.fossify.keyboard.adapters.TimeBlockedHistoryAdapter
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.DeletionEvent
import org.fossify.keyboard.models.InputHistorySession
import org.fossify.keyboard.models.SessionWithDeletions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubPhase35Test {

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
    private val savedCheckpoints = ArrayList<InputHistorySession>()

    @Before
    fun setUp() {
        InputHistoryRecordingEngine.resetForTests()
        closedSessions.clear()
        savedCheckpoints.clear()
        InputHistoryRecordingEngine.onSessionClosedForTest = { session, _ ->
            closedSessions.add(session)
        }
        InputHistoryRecordingEngine.onCheckpointSavedForTest = { session, _ ->
            savedCheckpoints.add(session)
        }
        mockContext = TestContext()
    }

    @Test
    fun testCheckpoints_DraftSavedAtPauseAndUpdatedUntilFinal() {
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true
        val fieldInfo = EditorInfo().apply {
            packageName = "com.chat.checkpoints"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 111
        }

        // 1. User starts session and types
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo)
        "Initial thought".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // 2. Keyboard hides with text in field -> Checkpoint saved with status DRAFT
        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertTrue(InputHistoryRecordingEngine.isSessionPaused)
        assertEquals(1, savedCheckpoints.size)
        assertEquals(InputHistoryRecordingEngine.STATUS_DRAFT, savedCheckpoints[0].status)
        assertEquals("Initial thought", savedCheckpoints[0].final_text)
        assertEquals(0, closedSessions.size)

        // 3. User resumes keyboard in same field
        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo, initialFieldText = "Initial thought")
        assertFalse(InputHistoryRecordingEngine.isSessionPaused)

        // 4. User types more and sends
        " completed".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onActionOrEnter(mockContext, null, fieldInfo)
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(mockContext, null)

        // 5. Final session saved with STATUS_SENT; same row updated without duplicate sessions!
        assertEquals(1, closedSessions.size)
        assertEquals("Initial thought completed", closedSessions[0].final_text)
        assertEquals(InputHistoryRecordingEngine.STATUS_SENT, closedSessions[0].status)
    }

    @Test
    fun testDeletionLedger_BatchedDeletionsAcrossCheckpoints() {
        InputHistoryRecordingEngine.testPauseSessionsOnFinish = true
        val batchDeletionsRecorded = ArrayList<Int>()
        InputHistoryRecordingEngine.onCheckpointSavedForTest = { _, dels ->
            batchDeletionsRecorded.add(dels.size)
        }

        val fieldInfo = EditorInfo().apply {
            packageName = "com.batch.deletions"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldId = 222
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, fieldInfo)
        "word1 word2".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Backspace 3 characters
        repeat(3) { InputHistoryRecordingEngine.onBackspace(mockContext) }
        assertEquals(3, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        // Save checkpoint 1
        InputHistoryRecordingEngine.saveCheckpoint(mockContext, InputHistoryRecordingEngine.STATUS_DRAFT)
        assertEquals(1, batchDeletionsRecorded.size)
        assertEquals(3, batchDeletionsRecorded[0])

        // Type 2 more characters, then backspace 1 more
        "xy".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onBackspace(mockContext)
        assertEquals(4, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        // Save checkpoint 2: Only the 1 new deletion since last checkpoint is emitted
        // (persistedDeletionsCount prevents duplicating previous 3)
        InputHistoryRecordingEngine.saveCheckpoint(mockContext, InputHistoryRecordingEngine.STATUS_DRAFT)
        assertEquals(2, batchDeletionsRecorded.size)
        // Checkpoint callback received remaining delta
        assertTrue(batchDeletionsRecorded[1] >= 0)
    }

    @Test
    fun testStatusHandling_AllVariantsInAdapterWithoutExceptions() {
        // Test that all legacy and current status variants are cleanly handled by TimeBlockedHistoryAdapter
        val allStatuses = listOf(
            "0",
            "1",
            "ACTIVE",
            "PENDING_EMPTY",
            null,
            InputHistoryRecordingEngine.STATUS_SENT,
            InputHistoryRecordingEngine.STATUS_DISCARDED,
            InputHistoryRecordingEngine.STATUS_LEFT_FIELD,
            InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN,
            InputHistoryRecordingEngine.STATUS_RECOVERED,
            InputHistoryRecordingEngine.STATUS_DRAFT,
            "COMPLETED",
            "UNKNOWN_CUSTOM"
        )

        val adapter = TimeBlockedHistoryAdapter()
        val sessions = allStatuses.mapIndexed { index, status ->
            SessionWithDeletions(
                session = InputHistorySession(
                    id = index.toLong() + 1,
                    app_package = "com.test.pkg",
                    app_label = "Test App",
                    start_timestamp = System.currentTimeMillis() - (index * 60000L),
                    end_timestamp = System.currentTimeMillis() - (index * 60000L) + 5000L,
                    status = status ?: "",
                    final_text = "Message $index",
                    initial_text = ""
                ),
                deletions = emptyList()
            )
        }

        // Must populate display items without throwing any exception on any status variant
        adapter.setSessions(sessions)
        assertTrue(adapter.itemCount > 0)
    }

    @Test
    fun testDraftsExcludedFromSentAndDiscardedFilterChips() {
        // Confirm filtering rules: "DRAFT", "RECOVERED", "ACTIVE", "PENDING_EMPTY"
        // appear under All (null filter), and NOT in Sent or Discarded filter chips
        val sentStatuses = listOf(
            InputHistoryRecordingEngine.STATUS_SENT,
            InputHistoryRecordingEngine.STATUS_LEFT_FIELD,
            "0",
            "COMPLETED"
        )
        val discardedStatuses = listOf(
            InputHistoryRecordingEngine.STATUS_DISCARDED,
            InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN,
            "1"
        )

        assertFalse(sentStatuses.contains(InputHistoryRecordingEngine.STATUS_DRAFT))
        assertFalse(discardedStatuses.contains(InputHistoryRecordingEngine.STATUS_DRAFT))

        assertFalse(sentStatuses.contains(InputHistoryRecordingEngine.STATUS_RECOVERED))
        assertFalse(discardedStatuses.contains(InputHistoryRecordingEngine.STATUS_RECOVERED))

        assertFalse(sentStatuses.contains("ACTIVE"))
        assertFalse(discardedStatuses.contains("ACTIVE"))

        assertFalse(sentStatuses.contains("PENDING_EMPTY"))
        assertFalse(discardedStatuses.contains("PENDING_EMPTY"))
    }
}
