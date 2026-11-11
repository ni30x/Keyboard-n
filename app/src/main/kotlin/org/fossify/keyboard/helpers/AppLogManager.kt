package org.fossify.keyboard.helpers

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogManager {
    private const val TAG = "AppLogManager"
    private const val MAX_MEMORY_LOGS = 1000
    private const val MAX_FILE_SIZE_BYTES = 250_000L // 250 KB
    private const val CRASH_FILE_NAME = "last_crash.log"
    private const val EVENT_LOG_FILE_NAME = "app_events.log"

    private val logBuffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null
    private var isInitialized = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    var onNewLogListener: ((String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
        if (isInitialized) return
        isInitialized = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to record crash: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        log("SYSTEM", "AppLogManager initialized. Process PID: ${android.os.Process.myPid()}")
    }

    fun log(tag: String, message: String) {
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = "[$timestamp] [$tag] $message"

        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_MEMORY_LOGS) {
            logBuffer.pollFirst()
        }

        appendToFile(entry)

        val listener = onNewLogListener
        if (listener != null) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                listener(entry)
            } else {
                mainHandler.post { listener(entry) }
            }
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val sw = StringWriter()
        throwable?.printStackTrace(PrintWriter(sw))
        val stackTraceStr = if (throwable != null) "\n$sw" else ""
        log(tag, "ERROR: $message$stackTraceStr")
    }

    internal fun recordCrash(thread: Thread, throwable: Throwable) {
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val deviceInfo = buildDeviceInfo()
        val crashReport = buildString {
            appendLine("================ FATAL CRASH REPORT ================")
            appendLine("Timestamp: $timestamp")
            appendLine("Thread: ${thread.name} (ID: ${thread.id})")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message ?: "No message"}")
            appendLine("----------------- DEVICE DETAILS -----------------")
            appendLine(deviceInfo)
            appendLine("------------------- STACK TRACE -------------------")
            appendLine(stackTrace)
            appendLine("====================================================")
        }

        try {
            val ctx = appContext
            if (ctx != null) {
                val crashFile = File(ctx.filesDir, CRASH_FILE_NAME)
                crashFile.writeText(crashReport)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing crash file: ${e.message}")
        }

        log("FATAL_CRASH", "Uncaught exception on thread ${thread.name}: ${throwable.message}\n$stackTrace")
    }

    fun hasCrash(): Boolean {
        val ctx = appContext ?: return false
        val crashFile = File(ctx.filesDir, CRASH_FILE_NAME)
        return crashFile.exists() && crashFile.length() > 0
    }

    fun getLastCrash(): String? {
        val ctx = appContext ?: return null
        val crashFile = File(ctx.filesDir, CRASH_FILE_NAME)
        return if (crashFile.exists() && crashFile.length() > 0) {
            try {
                crashFile.readText()
            } catch (e: Exception) {
                "Error reading crash file: ${e.message}"
            }
        } else {
            null
        }
    }

    fun getFormattedLogs(): String {
        val sb = StringBuilder()
        sb.appendLine("================ FOSSIFY KEYBOARD DIAGNOSTIC LOG ================")
        sb.appendLine("Generated At: ${synchronized(timeFormat) { timeFormat.format(Date()) }}")
        sb.appendLine(buildDeviceInfo())
        sb.appendLine("==================================================================")
        sb.appendLine()

        val lastCrash = getLastCrash()
        if (lastCrash != null) {
            sb.appendLine("!!! LAST UNCAUGHT CRASH DETECTED !!!")
            sb.appendLine(lastCrash)
            sb.appendLine()
        } else {
            sb.appendLine(">>> CRASH STATUS: No crashes recorded.")
            sb.appendLine()
        }

        sb.appendLine("===================== RECENT EVENT LOGS =====================")
        val inMemoryLogs = logBuffer.toList()
        if (inMemoryLogs.isNotEmpty()) {
            for (line in inMemoryLogs) {
                sb.appendLine(line)
            }
        } else {
            val fileLogs = readEventLogFile()
            if (fileLogs.isNotEmpty()) {
                sb.appendLine(fileLogs)
            } else {
                sb.appendLine("(No event logs recorded yet)")
            }
        }
        sb.appendLine("======================= END OF LOGS =======================")
        return sb.toString()
    }

    fun clearLogs() {
        logBuffer.clear()
        val ctx = appContext ?: return
        try {
            val crashFile = File(ctx.filesDir, CRASH_FILE_NAME)
            if (crashFile.exists()) {
                crashFile.delete()
            }
            val logFile = File(ctx.filesDir, EVENT_LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing logs: ${e.message}")
        }
        log("SYSTEM", "Logs cleared by user")
    }

    private fun appendToFile(entry: String) {
        val ctx = appContext ?: return
        try {
            val logFile = File(ctx.filesDir, EVENT_LOG_FILE_NAME)
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE_BYTES) {
                val content = logFile.readText()
                val truncated = content.takeLast(50_000)
                logFile.writeText("... [TRUNCATED OLD LOGS] ...\n$truncated")
            }
            logFile.appendText("$entry\n")
        } catch (_: Exception) {
        }
    }

    private fun readEventLogFile(): String {
        val ctx = appContext ?: return ""
        val logFile = File(ctx.filesDir, EVENT_LOG_FILE_NAME)
        return if (logFile.exists()) {
            try {
                logFile.readText()
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    private fun buildDeviceInfo(): String {
        val ctx = appContext
        val versionName = try {
            val pInfo = ctx?.packageManager?.getPackageInfo(ctx.packageName, 0)
            pInfo?.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        val memoryClass = try {
            val actManager = ctx?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            "${actManager?.memoryClass ?: "?"} MB"
        } catch (e: Exception) {
            "?"
        }

        return "App Version: $versionName | Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) | Device: ${Build.MANUFACTURER} ${Build.MODEL} | Memory Class: $memoryClass"
    }
}
