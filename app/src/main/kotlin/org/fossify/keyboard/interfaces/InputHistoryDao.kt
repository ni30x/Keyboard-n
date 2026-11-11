package org.fossify.keyboard.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.fossify.keyboard.models.InputHistorySession
import org.fossify.keyboard.models.SessionWithDeletions

@Dao
interface InputHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: InputHistorySession): Long

    @Query("SELECT * FROM input_history ORDER BY id DESC")
    fun getAllSessions(): Flow<List<InputHistorySession>>

    @Query("SELECT * FROM input_history ORDER BY id DESC")
    fun getAllSessionsList(): List<InputHistorySession>

    @Transaction
    @Query("SELECT * FROM input_history ORDER BY id DESC")
    fun getAllSessionsWithDeletions(): Flow<List<SessionWithDeletions>>

    @Transaction
    @Query("SELECT * FROM input_history WHERE status = :status ORDER BY id DESC")
    fun getSessionsWithDeletionsByStatus(status: String): Flow<List<SessionWithDeletions>>

    @Query("SELECT * FROM input_history WHERE status = :status ORDER BY id DESC")
    fun getSessionsByStatus(status: String): List<InputHistorySession>

    @Query("DELETE FROM input_history WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM input_history")
    fun clearAll()

    @Query("SELECT start_timestamp FROM input_history")
    fun getAllSessionTimestamps(): Flow<List<Long>>

    @Query("SELECT DISTINCT app_package FROM input_history")
    fun getDistinctPackages(): List<String>

    @Transaction
    @Query("SELECT * FROM input_history WHERE start_timestamp >= :startTime AND start_timestamp <= :endTime ORDER BY start_timestamp ASC")
    fun getSessionsWithDeletionsInRange(startTime: Long, endTime: Long): Flow<List<SessionWithDeletions>>

    @Transaction
    @Query("SELECT * FROM input_history WHERE start_timestamp >= :startTime AND start_timestamp <= :endTime AND status = :status ORDER BY start_timestamp ASC")
    fun getSessionsWithDeletionsInRangeAndStatus(startTime: Long, endTime: Long, status: String): Flow<List<SessionWithDeletions>>

    @Transaction
    @Query("SELECT * FROM input_history WHERE start_timestamp >= :startTime AND start_timestamp <= :endTime AND status IN (:statuses) ORDER BY start_timestamp ASC")
    fun getSessionsWithDeletionsInRangeAndStatuses(startTime: Long, endTime: Long, statuses: List<String>): Flow<List<SessionWithDeletions>>

    @androidx.room.Update
    fun updateSession(session: InputHistorySession)

    @Query("UPDATE input_history SET status = 'RECOVERED' WHERE status = 'DRAFT' AND id != :activeRowId AND start_timestamp < :processStartTime")
    fun markOldDraftsAsRecovered(activeRowId: Long, processStartTime: Long): Int

    @Query("DELETE FROM input_history WHERE start_timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: Long): Int
}
