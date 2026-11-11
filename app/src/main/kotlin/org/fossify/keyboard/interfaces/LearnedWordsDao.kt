package org.fossify.keyboard.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.keyboard.models.LearnedWord

@Dao
interface LearnedWordsDao {
    @Query("SELECT * FROM learned_words ORDER BY frequency DESC, last_used DESC LIMIT 2000")
    fun getAll(): List<LearnedWord>

    @Query("SELECT * FROM learned_words WHERE word = :word LIMIT 1")
    fun getWord(word: String): LearnedWord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(learnedWord: LearnedWord)

    @Query("UPDATE learned_words SET frequency = frequency + 1, last_used = :timestamp WHERE word = :word")
    fun incrementFrequency(word: String, timestamp: Long): Int

    @Query("DELETE FROM learned_words WHERE word = :word")
    fun deleteWord(word: String)

    @Query("DELETE FROM learned_words")
    fun clearAll()
}
