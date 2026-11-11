package org.fossify.keyboard.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.fossify.keyboard.models.DeletionEvent

@Dao
interface DeletionEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(event: DeletionEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(events: List<DeletionEvent>)

    @Query("SELECT * FROM deletion_events WHERE session_id = :sessionId ORDER BY position ASC, id ASC")
    fun getDeletionsForSession(sessionId: Long): List<DeletionEvent>

    @Query("SELECT * FROM deletion_events WHERE session_id = :sessionId ORDER BY position ASC, id ASC")
    fun getDeletionsForSessionFlow(sessionId: Long): Flow<List<DeletionEvent>>

    @Query("DELETE FROM deletion_events")
    fun clearAll()
}
