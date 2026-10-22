package org.fossify.keyboard.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "learned_words")
data class LearnedWord(
    @PrimaryKey
    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "frequency")
    val frequency: Int = 1,

    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis()
)
