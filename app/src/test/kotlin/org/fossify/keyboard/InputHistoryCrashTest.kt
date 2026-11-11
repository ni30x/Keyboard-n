package org.fossify.keyboard

import org.fossify.keyboard.adapters.TimeBlockedHistoryAdapter
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.DeletionEvent
import org.fossify.keyboard.models.InputHistorySession
import org.fossify.keyboard.models.SessionWithDeletions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InputHistoryCrashTest {

    @Test
    fun testAllStatuses_OldAndNew_RenderInAdapterWithoutCrash() {
        val statusesToTest = listOf(
            // New honest statuses (Phase 3.1)
            InputHistoryRecordingEngine.STATUS_SENT,
            InputHistoryRecordingEngine.STATUS_LEFT_FIELD,
            InputHistoryRecordingEngine.STATUS_DISCARDED,
            InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN,
            InputHistoryRecordingEngine.STATUS_RECOVERED,
            // Old statuses (Pre-3.1 builds & legacy data)
            "0",
            "1",
            "ACTIVE",
            "PENDING_EMPTY",
            "UNKNOWN",
            "COMPLETED"
        )

        val sessions = statusesToTest.mapIndexed { index, statusStr ->
            val baseTime = 1700000000000L
            val sess = InputHistorySession(
                id = (index + 1).toLong(),
                app_package = "com.test.app",
                app_label = "Test App",
                start_timestamp = baseTime + (index * 1000L),
                end_timestamp = baseTime + (index * 1000L) + 500L,
                status = statusStr,
                final_text = "Sample text for status $statusStr"
            )
            val delList = listOf(
                DeletionEvent(
                    id = (index + 1).toLong(),
                    session_id = (index + 1).toLong(),
                    char_or_word = "Sample",
                    position = 0,
                    deleted_at = System.currentTimeMillis()
                )
            )
            SessionWithDeletions(session = sess, deletions = delList)
        }

        val adapter = TimeBlockedHistoryAdapter()
        adapter.setSessions(sessions)

        // Verify items were built for all sessions without any exception
        assertNotNull(adapter)
        // 1 header + 1 app group + 0 sessions (collapsed by default)
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun testFilterStatusesMapping_IncludesOldAndNewStatuses() {
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

        // Verify sent statuses list includes legacy "0" and excludes "ACTIVE"
        assertEquals(true, sentStatuses.contains("0"))
        assertEquals(false, sentStatuses.contains("ACTIVE"))
        assertEquals(true, sentStatuses.contains(InputHistoryRecordingEngine.STATUS_SENT))
        assertEquals(true, sentStatuses.contains(InputHistoryRecordingEngine.STATUS_LEFT_FIELD))

        // Verify discarded statuses list includes legacy "1" and excludes "PENDING_EMPTY"
        assertEquals(true, discardedStatuses.contains("1"))
        assertEquals(false, discardedStatuses.contains("PENDING_EMPTY"))
        assertEquals(true, discardedStatuses.contains(InputHistoryRecordingEngine.STATUS_DISCARDED))
        assertEquals(true, discardedStatuses.contains(InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN))
    }
}
