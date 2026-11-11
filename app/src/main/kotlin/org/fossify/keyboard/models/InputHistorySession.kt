package org.fossify.keyboard.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "input_history")
data class InputHistorySession(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "app_package")
    val app_package: String,

    @ColumnInfo(name = "app_label")
    val app_label: String,

    @ColumnInfo(name = "start_timestamp")
    val start_timestamp: Long,

    @ColumnInfo(name = "end_timestamp")
    val end_timestamp: Long,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "final_text")
    val final_text: String,

    @ColumnInfo(name = "initial_text", defaultValue = "")
    val initial_text: String = ""
)
