package org.fossify.keyboard.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.fossify.keyboard.interfaces.LearnedWordsDao
import org.fossify.keyboard.models.LearnedWord

@Database(entities = [LearnedWord::class], version = 1)
abstract class LearnedWordsDatabase : RoomDatabase() {

    abstract fun learnedWordsDao(): LearnedWordsDao

    companion object {
        private var db: LearnedWordsDatabase? = null

        fun getInstance(context: Context): LearnedWordsDatabase {
            if (db == null) {
                synchronized(LearnedWordsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context.applicationContext,
                            LearnedWordsDatabase::class.java,
                            "learned_words.db"
                        ).build()
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
