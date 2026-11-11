package org.fossify.keyboard

import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.keyboard.databases.InputHistoryDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MigrationSafetyTest {

    @Test
    fun testMigration1To2_PreservesInputHistoryAndCreatesDeletionEvents() {
        val executedSqlStatements = ArrayList<String>()

        val mockDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSqlStatements.add(args[0].toString())
            }
            null
        } as SupportSQLiteDatabase

        // Verify migration metadata
        assertEquals(1, InputHistoryDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, InputHistoryDatabase.MIGRATION_1_2.endVersion)

        // Execute migration
        InputHistoryDatabase.MIGRATION_1_2.migrate(mockDb)

        // 1. Verify deletion_events table was created
        val tableSql = executedSqlStatements.find { it.contains("CREATE TABLE IF NOT EXISTS `deletion_events`") }
        assertTrue("Expected CREATE TABLE for deletion_events", tableSql != null)
        assertTrue("Expected foreign key cascade to input_history", tableSql!!.contains("FOREIGN KEY(`session_id`) REFERENCES `input_history`(`id`)"))

        // 2. Verify index on session_id was created
        val indexSql = executedSqlStatements.find { it.contains("CREATE INDEX IF NOT EXISTS `index_deletion_events_session_id`") }
        assertTrue("Expected CREATE INDEX on deletion_events(session_id)", indexSql != null)

        // 3. Verify input_history was never dropped or altered destructively
        for (sql in executedSqlStatements) {
            assertFalse("Migration must never execute DROP TABLE on input_history", sql.uppercase().contains("DROP TABLE"))
        }
    }

    @Test
    fun testMigration2To3_PreservesAllRowsAndStatuses() {
        val executedSqlStatements = ArrayList<String>()

        val mockDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSqlStatements.add(args[0].toString())
            }
            null
        } as SupportSQLiteDatabase

        // Verify migration 2->3 metadata
        assertEquals(2, InputHistoryDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, InputHistoryDatabase.MIGRATION_2_3.endVersion)

        // Execute migration
        InputHistoryDatabase.MIGRATION_2_3.migrate(mockDb)

        // 1. Verify initial_text column addition
        val alterSql = executedSqlStatements.find { it.contains("ALTER TABLE `input_history` ADD COLUMN `initial_text`") }
        assertTrue("Expected ALTER TABLE adding initial_text column", alterSql != null)
        assertTrue("Expected default value for initial_text", alterSql!!.contains("DEFAULT ''"))

        // 2. Verify input_history was never dropped
        for (sql in executedSqlStatements) {
            assertFalse("Migration 2->3 must never drop input_history", sql.uppercase().contains("DROP TABLE"))
        }
    }
}
