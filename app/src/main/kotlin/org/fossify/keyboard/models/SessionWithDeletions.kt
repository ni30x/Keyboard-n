package org.fossify.keyboard.models

import androidx.room.Embedded
import androidx.room.Relation

data class SessionWithDeletions(
    @Embedded
    val session: InputHistorySession,

    @Relation(
        parentColumn = "id",
        entityColumn = "session_id"
    )
    val deletions: List<DeletionEvent>
)
