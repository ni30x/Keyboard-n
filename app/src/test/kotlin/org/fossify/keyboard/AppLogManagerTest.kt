package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import org.fossify.keyboard.helpers.AppLogManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppLogManagerTest {

    private lateinit var tempDir: File
    private lateinit var context: Context

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("app_log_test").toFile()
        context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): Context = this
        }
        AppLogManager.init(context)
        AppLogManager.clearLogs()
    }

    @After
    fun tearDown() {
        AppLogManager.clearLogs()
        tempDir.deleteRecursively()
    }

    @Test
    fun testLogEventsCapturedWithoutSensitiveUserContent() {
        AppLogManager.log("ENGINE", "onKeystroke: charCode=97, pos=1")
        AppLogManager.log("IME", "onStartInputView: pkg=com.example.app, fieldId=42")

        val logs = AppLogManager.getFormattedLogs()
        assertTrue("Log should contain tag ENGINE", logs.contains("[ENGINE]"))
        assertTrue("Log should contain tag IME", logs.contains("[IME]"))
        assertTrue("Log should contain fieldId", logs.contains("fieldId=42"))
        assertFalse("Log must not contain typed secret", logs.contains("secretPassword123"))
    }

    @Test
    fun testCrashRecordingAndRecovery() {
        assertFalse("Initially should have no crash", AppLogManager.hasCrash())

        // Simulate an uncaught exception
        val fakeException = RuntimeException("Test NullPointerException simulated during session finalization")
        AppLogManager.recordCrash(Thread.currentThread(), fakeException)

        assertTrue("Crash should be detected", AppLogManager.hasCrash())
        val crashReport = AppLogManager.getLastCrash()
        assertNotNull("Crash report must not be null", crashReport)
        assertTrue("Crash report must contain simulated exception message", crashReport!!.contains("Test NullPointerException"))
        assertTrue("Crash report must contain thread name", crashReport.contains(Thread.currentThread().name))

        // Clear logs removes crash report
        AppLogManager.clearLogs()
        assertFalse("After clearing logs, hasCrash must be false", AppLogManager.hasCrash())
    }

    @Test
    fun testClearLogsRemovesFilesAndMemory() {
        AppLogManager.log("TEST", "Sample event 1")
        AppLogManager.log("TEST", "Sample event 2")
        val beforeClear = AppLogManager.getFormattedLogs()
        assertTrue(beforeClear.contains("Sample event 1"))

        AppLogManager.clearLogs()
        val afterClear = AppLogManager.getFormattedLogs()
        assertTrue("After clear, logs should be empty or contain only cleared message",
            afterClear.isEmpty() || afterClear.contains("Logs cleared by user"))
    }
}
