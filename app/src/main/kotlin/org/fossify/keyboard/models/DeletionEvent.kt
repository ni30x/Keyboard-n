package org.fossify.keyboard.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "deletion_events",
    foreignKeys = [
        ForeignKey(
            entity = InputHistorySession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"])]
)
data class DeletionEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val session_id: Long,

    @ColumnInfo(name = "char_or_word")
    val char_or_word: String,

    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "deleted_at")
    val deleted_at: Long
)
