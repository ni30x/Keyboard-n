package org.fossify.keyboard.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.fossify.keyboard.interfaces.DeletionEventDao
import org.fossify.keyboard.interfaces.InputHistoryDao
import org.fossify.keyboard.models.DeletionEvent
import org.fossify.keyboard.models.InputHistorySession

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [InputHistorySession::class, DeletionEvent::class],
    version = 3,
    exportSchema = true
)
abstract class InputHistoryDatabase : RoomDatabase() {

    abstract fun inputHistoryDao(): InputHistoryDao
    abstract fun deletionEventDao(): DeletionEventDao

    companion object {
        private var db: InputHistoryDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `deletion_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id` INTEGER NOT NULL,
                        `char_or_word` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `deleted_at` INTEGER NOT NULL,
                        FOREIGN KEY(`session_id`) REFERENCES `input_history`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deletion_events_session_id` ON `deletion_events` (`session_id`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `input_history` ADD COLUMN `initial_text` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): InputHistoryDatabase {
            if (db == null) {
                synchronized(InputHistoryDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context.applicationContext,
                            InputHistoryDatabase::class.java,
                            "input_history.db"
                        )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .build()
                        db!!.openHelper.setWriteAheadLoggingEnabled(true)
                    }
                }
            }
            return db!!
        }

        fun destroyInstance() {
            db = null
        }
    }
}
