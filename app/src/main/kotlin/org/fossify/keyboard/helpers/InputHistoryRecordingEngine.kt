package org.fossify.keyboard.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import org.fossify.keyboard.BuildConfig
import org.fossify.keyboard.databases.InputHistoryDatabase
import org.fossify.keyboard.models.DeletionEvent
import org.fossify.keyboard.models.InputHistorySession

object InputHistoryRecordingEngine {

    const val STATUS_SENT = "SENT"
    const val STATUS_LEFT_FIELD = "LEFT_FIELD"
    const val STATUS_DISCARDED = "DISCARDED"
    const val STATUS_CLEARED_UNKNOWN = "CLEARED_UNKNOWN"
    const val STATUS_RECOVERED = "RECOVERED"
    const val STATUS_DRAFT = "DRAFT"

    val processStartTime: Long = System.currentTimeMillis()
    val engineLock = Any()

    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    enum class LiveState {
        ACTIVE,
        PENDING_EMPTY
    }

    @Volatile
    var liveState: LiveState = LiveState.ACTIVE
        private set

    // App-switch detection window threshold
    const val APP_SWITCH_WINDOW_MS = 300L

    data class PendingDeletion(
        val char_or_word: String,
        val position: Int,
        val deleted_at: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isRecordingActive: Boolean = false
        private set

    @Volatile
    var currentPackage: String = "unknown"
        private set

    @Volatile
    var currentAppLabel: String = "unknown"
        private set

    @Volatile
    private var sessionStartTimestamp: Long = 0L

    @Volatile
    var charCounter: Int = 0
        private set

    @Volatile
    private var sawSelectionDelete: Boolean = false

    // Non-destructive working buffer: characters are never removed on deletion
    private var workingBuffer: StringBuilder? = null

    // Tracks indices in workingBuffer of characters that are currently live/un-deleted
    private val liveCharIndices = ArrayList<Int>()

    // Deletion ledger tracking deleted spans and original positions
    private val pendingDeletions = ArrayList<PendingDeletion>()

    @Volatile
    private var isSequentialDeletion: Boolean = false

    @Volatile
    var lastKeystrokeTimestamp: Long = 0L
        private set

    @Volatile
    private var lastActionTimestamp: Long = 0L

    @Volatile
    private var lastActionWasSubmit: Boolean = false

    // Sub-phase 3.4: Single constant for delayed empty check
    const val EMPTY_CHECK_DELAY_MS = 300L

    var emptyCheckDelayMs: Long = EMPTY_CHECK_DELAY_MS

    private var pendingEmptyJob: Job? = null

    @Volatile
    var currentSessionRowId: Long = 0L
        private set

    @Volatile
    private var persistedDeletionsCount: Int = 0

    private var lastCheckpointKeystrokeCount: Int = 0
    private var lastCheckpointTimestamp: Long = 0L
    const val CHECKPOINT_INTERVAL_MS = 5000L
    const val CHECKPOINT_KEYSTROKE_INTERVAL = 30

    @Volatile
    var initialText: String = ""
        private set

    @Volatile
    var composingText: String = ""
        private set

    @Volatile
    var cursorPosition: Int = 0
        private set

    @Volatile
    private var sessionFieldKey: String = ""

    @Volatile
    var isSessionPaused: Boolean = false
        private set

    fun isPasswordInputType(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        } else if (inputClass == InputType.TYPE_CLASS_NUMBER) {
            if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                return true
            }
        }
        return false
    }

    private val SENSITIVE_AUTOFILL_HINTS = setOf(
        "password", "password_new", "new_password", "current_password",
        "credit_card_number", "creditcardnumber", "credit_card_security_code",
        "creditcardsecuritycode", "cvv", "cvc", "security_code", "securitycode",
        "otp", "2fa", "one_time_code", "one-time-code", "verification_code", "pin"
    )

    private val SENSITIVE_WORD_REGEX = Regex(
        """\b(otp|2fa|cvv|cvc|pin|pin\s*code|one\s*[-_]?\s*time\s*[-_]?\s*code|card\s*number|credit\s*card|verification\s*code)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Sub-phase 3.6 item 1 / Round 2 item 4: OTP, PIN, Card and sensitive field detection heuristics.
     * Matches exact autofill hint tokens and whole words only, never substrings.
     */
    fun isSensitiveField(attribute: EditorInfo?): Boolean {
        if (attribute == null) return false
        val inputType = attribute.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return true
        }

        if (inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            return true
        }

        val extrasHints = attribute.extras?.getStringArray("android.view.autofill.extra.HINTS")
        if (extrasHints != null) {
            for (hint in extrasHints) {
                val normalized = hint.lowercase().trim()
                if (normalized in SENSITIVE_AUTOFILL_HINTS) {
                    return true
                }
            }
        }

        val fieldName = attribute.fieldName ?: ""
        val hintText = attribute.hintText?.toString() ?: ""
        val combined = "$fieldName $hintText".replace('_', ' ').replace('-', ' ')
        if (SENSITIVE_WORD_REGEX.containsMatchIn(combined)) {
            return true
        }

        return false
    }

    /**
     * Updates candidate composing text (Sub-phase 3.3).
     */
    fun onComposingUpdate(text: String) {
        composingText = text
    }

    /**
     * Reconstructs the live shadow text (visible text in the field) from liveCharIndices.
     * Round 2 item 3: Compare returning field text with this, NOT the append-only workingBuffer.
     */
    fun getLiveShadowText(): String {
        synchronized(engineLock) {
            val buf = workingBuffer ?: return ""
            val sb = StringBuilder()
            for (idx in liveCharIndices) {
                if (idx in 0 until buf.length) {
                    sb.append(buf[idx])
                }
            }
            return sb.toString()
        }
    }

    /**
     * Inspects EditorInfo on onStartInput().
     * Captures initial text snapshot (3.3.a).
     * Handles Session Identity & Resume across keyboard hide/reshow in same field (3.4.c / Round 2 item 3).
     */
    fun onStartInput(context: Context, attribute: EditorInfo?, initialFieldText: String? = null) {
        val inputType = attribute?.inputType ?: InputType.TYPE_NULL
        val newPackage = attribute?.packageName ?: "unknown"
        val now = System.currentTimeMillis()
        val newFieldKey = "$newPackage:${attribute?.fieldId ?: 0}:$inputType:${attribute?.imeOptions ?: 0}"

        // Background task on keyboard start: mark old unfinished drafts as RECOVERED (Sub-phase 3.5 / Round 2 item 7)
        // and purge records older than retention period (Sub-phase 3.6 item 4)
        if (!isTestMode) {
            val appContext = context.applicationContext
            val activeId = currentSessionRowId
            scope.launch(ioDispatcher) {
                try {
                    val db = InputHistoryDatabase.getInstance(appContext)
                    db.inputHistoryDao().markOldDraftsAsRecovered(activeId, processStartTime)
                    val config = Config.newInstance(appContext)
                    val retentionDays = config.historyRetentionDays
                    if (retentionDays > 0) {
                        val cutoff = now - (retentionDays.toLong() * 86_400_000L)
                        db.inputHistoryDao().deleteOlderThan(cutoff)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e("IHDBG", "Error in onStartInput background tasks: ${e.message}")
                    }
                }
            }
        }

        IhDebug.log(
            event = "onStartInput",
            sessionId = now,
            charCounter = charCounter,
            bufferLen = workingBuffer?.length ?: 0,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = newPackage,
            extra = "isRecordingActive=$isRecordingActive currentPkg=$currentPackage paused=$isSessionPaused"
        )

        // 3.4.c / Round 2 item 3 Session Resume:
        // When a PAUSED session meets a returning keyboard in same field key, do NOT resume on key match alone.
        // Read real field text and compare it with LIVE shadow text. If empty or differs, finalize old session as LEFT_FIELD.
        if (isSessionPaused && isRecordingActive && newFieldKey == sessionFieldKey && currentPackage == newPackage && charCounter > 0) {
            val liveShadow = getLiveShadowText()
            val fieldMatches = !initialFieldText.isNullOrEmpty() && initialFieldText == liveShadow
            if (fieldMatches) {
                isSessionPaused = false
                liveState = LiveState.ACTIVE
                IhDebug.log("onStartInput_resumed_session", now, pkg = newPackage)
                return
            } else {
                closeSession(context, STATUS_LEFT_FIELD)
            }
        } else if (isSessionPaused && charCounter > 0) {
            // Field or app changed while paused: finalize previous session as LEFT_FIELD
            closeSession(context, STATUS_LEFT_FIELD)
        }

        // 1. Detect foreground app changes while an active session exists
        val isAppSwitch = isRecordingActive && currentPackage.isNotEmpty() && currentPackage != "unknown" && newPackage != currentPackage

        if (isAppSwitch && charCounter > 0) {
            val status = if (lastActionWasSubmit || (lastActionTimestamp > 0 && lastActionTimestamp >= lastKeystrokeTimestamp)) {
                STATUS_SENT
            } else {
                STATUS_LEFT_FIELD
            }
            closeSession(context, status)
        }

        // 2. Check if recording is disabled globally or for this specific app
        if (isTestMode) {
            if (!testRecordInputHistory || testBlockedPackages.contains(newPackage)) {
                IhDebug.log("onStartInput_blocked_or_disabled", now, pkg = newPackage)
                stopRecording()
                return
            }
        } else {
            val config = Config.newInstance(context)
            if (!config.recordInputHistory || config.blockedHistoryPackages.contains(newPackage)) {
                IhDebug.log("onStartInput_blocked_or_disabled", now, pkg = newPackage)
                stopRecording()
                return
            }
        }

        // Sub-phase 3.6 item 1: Incognito flag (IME_FLAG_NO_PERSONALIZED_LEARNING)
        val isIncognito = attribute != null && ((attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0)
        if (isIncognito) {
            IhDebug.log("onStartInput_incognito_skipped", now, pkg = newPackage)
            stopRecording()
            return
        }

        // 3. Check if target field is password variation or sensitive (OTP / Card / Sensitive)
        if (isPasswordInputType(inputType) || isSensitiveField(attribute)) {
            IhDebug.log("onStartInput_sensitive_skipped", now, pkg = newPackage)
            stopRecording()
            return
        }

        // 4. Open a fresh session scoped to the target field & app
        isRecordingActive = true
        currentPackage = newPackage
        currentAppLabel = resolveAppLabel(context, newPackage)
        sessionStartTimestamp = now
        sessionFieldKey = newFieldKey
        isSessionPaused = false
        liveState = LiveState.ACTIVE
        lastActionWasSubmit = false
        lastActionTimestamp = 0L
        lastKeystrokeTimestamp = 0L
        lastCheckpointKeystrokeCount = 0
        lastCheckpointTimestamp = 0L
        currentSessionRowId = 0L
        persistedDeletionsCount = 0
        isSequentialDeletion = false
        sawSelectionDelete = false
        composingText = ""

        // Sub-phase 3.3.a: Initial text snapshot
        val initialStr = initialFieldText ?: ""
        initialText = initialStr
        liveCharIndices.clear()
        pendingDeletions.clear()

        if (initialStr.isNotEmpty()) {
            workingBuffer = StringBuilder(initialStr)
            charCounter = initialStr.length
            for (i in 0 until initialStr.length) {
                liveCharIndices.add(i)
            }
            cursorPosition = initialStr.length
        } else {
            workingBuffer = StringBuilder()
            charCounter = 0
            cursorPosition = 0
        }
    }

    fun onCursorMoved(newPosition: Int) {
        val maxLen = workingBuffer?.length ?: 0
        cursorPosition = newPosition.coerceIn(0, maxLen)
    }

    fun cancelPendingEmpty() {
        pendingEmptyJob?.cancel()
        pendingEmptyJob = null
        if (liveState == LiveState.PENDING_EMPTY) {
            liveState = LiveState.ACTIVE
        }
    }

    /**
     * Non-destructive keystroke handling:
     * Inserts text at cursorPosition (supporting middle-of-text edits)
     * and triggers periodic draft checkpoints (Sub-phase 3.5).
     */
    fun onKeystroke(text: String, context: Context? = null) {
        if (!isRecordingActive || text.isEmpty()) return
        cancelPendingEmpty()

        lastKeystrokeTimestamp = System.currentTimeMillis()
        lastActionWasSubmit = false

        val buf = workingBuffer ?: return
        if (cursorPosition >= buf.length) {
            for (ch in text) {
                val pos = buf.length
                buf.append(ch)
                liveCharIndices.add(pos)
                charCounter++
                cursorPosition++
            }
        } else {
            // Insertion at cursor in middle of text (3.3.b / Step 0.e)
            buf.insert(cursorPosition, text)
            for (i in 0 until liveCharIndices.size) {
                if (liveCharIndices[i] >= cursorPosition) {
                    liveCharIndices[i] += text.length
                }
            }
            for (i in 0 until text.length) {
                liveCharIndices.add(cursorPosition + i)
            }
            liveCharIndices.sort()
            charCounter += text.length
            cursorPosition += text.length
        }
        isSequentialDeletion = false
        sawSelectionDelete = false

        // Checkpoint periodic trigger (Sub-phase 3.5: every 30 chars or 5s)
        if (context != null) {
            val now = System.currentTimeMillis()
            if ((charCounter - lastCheckpointKeystrokeCount >= CHECKPOINT_KEYSTROKE_INTERVAL) ||
                (lastCheckpointTimestamp > 0 && now - lastCheckpointTimestamp >= CHECKPOINT_INTERVAL_MS)
            ) {
                saveCheckpoint(context, STATUS_DRAFT)
            }
        }

        IhDebug.log(
            event = "onKeystroke",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = buf.length,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "appendLen=${text.length} cursorPos=$cursorPosition"
        )
    }

    fun onKeystroke(char: Char, context: Context? = null) {
        onKeystroke(char.toString(), context)
    }

    /**
     * Non-destructive deletion:
     * Does NOT remove characters from workingBuffer.
     * Appends {char_or_word, position, deleted_at} to deletion ledger.
     * Respects cursor position for middle-of-text deletions.
     */
    fun onBackspace(context: Context) {
        if (!isRecordingActive) return
        cancelPendingEmpty()

        lastKeystrokeTimestamp = System.currentTimeMillis()
        lastActionWasSubmit = false

        if (liveCharIndices.isNotEmpty()) {
            val bufferPos = if (cursorPosition > 0 && cursorPosition <= (workingBuffer?.length ?: 0)) {
                val target = cursorPosition - 1
                val idxInList = liveCharIndices.indexOfLast { it <= target }
                if (idxInList != -1) {
                    liveCharIndices.removeAt(idxInList)
                } else {
                    liveCharIndices.removeAt(liveCharIndices.size - 1)
                }
            } else {
                liveCharIndices.removeAt(liveCharIndices.size - 1)
            }

            val deletedChar = workingBuffer?.getOrNull(bufferPos)?.toString() ?: ""
            if (charCounter > 0) {
                charCounter--
            }
            if (cursorPosition > 0) {
                cursorPosition--
            }
            isSequentialDeletion = true

            pendingDeletions.add(
                PendingDeletion(
                    char_or_word = deletedChar,
                    position = bufferPos,
                    deleted_at = System.currentTimeMillis()
                )
            )

            IhDebug.log(
                event = "onBackspace",
                sessionId = sessionStartTimestamp,
                charCounter = charCounter,
                bufferLen = workingBuffer?.length ?: 0,
                liveIndicesSize = liveCharIndices.size,
                deletionsSize = pendingDeletions.size,
                pkg = currentPackage,
                extra = "delPos=$bufferPos cursorPos=$cursorPosition"
            )

            if (charCounter == 0) {
                closeSession(context, STATUS_DISCARDED)
            }
        }
    }

    /**
     * Non-destructive multi-character / word deletion.
     */
    fun onWordDeleted(context: Context, count: Int) {
        if (!isRecordingActive || liveCharIndices.isEmpty()) return
        cancelPendingEmpty()

        lastKeystrokeTimestamp = System.currentTimeMillis()
        lastActionWasSubmit = false

        val numToDelete = count.coerceAtMost(liveCharIndices.size)
        val deletedChars = StringBuilder()
        var startPos = -1

        for (i in 0 until numToDelete) {
            val bufferPos = liveCharIndices.removeAt(liveCharIndices.size - 1)
            val ch = workingBuffer?.getOrNull(bufferPos)
            if (ch != null) {
                deletedChars.insert(0, ch)
            }
            startPos = bufferPos
        }

        if (deletedChars.isNotEmpty()) {
            charCounter = (charCounter - numToDelete).coerceAtLeast(0)
            cursorPosition = (cursorPosition - numToDelete).coerceAtLeast(0)
            isSequentialDeletion = true
            pendingDeletions.add(
                PendingDeletion(
                    char_or_word = deletedChars.toString(),
                    position = startPos,
                    deleted_at = System.currentTimeMillis()
                )
            )

            IhDebug.log(
                event = "onWordDeleted",
                sessionId = sessionStartTimestamp,
                charCounter = charCounter,
                bufferLen = workingBuffer?.length ?: 0,
                liveIndicesSize = liveCharIndices.size,
                deletionsSize = pendingDeletions.size,
                pkg = currentPackage,
                extra = "count=$count deletedLen=$numToDelete startPos=$startPos"
            )

            if (charCounter == 0) {
                closeSession(context, STATUS_DISCARDED)
            }
        }
    }

    /**
     * Inspects field state on selection/cursor updates or editor actions.
     * Delayed empty check (Step 0.d / 3.4.a): schedules pending empty check when field drops to zero.
     */
    fun onFieldOrSelectionUpdated(context: Context, inputConnection: InputConnection?) {
        if (!isRecordingActive || charCounter == 0) return

        val textBefore = inputConnection?.getTextBeforeCursor(200, 0)
        val textAfter = inputConnection?.getTextAfterCursor(200, 0)
        val currentLength = (textBefore?.length ?: 0) + (textAfter?.length ?: 0)

        IhDebug.log(
            event = "onFieldOrSelectionUpdated",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = workingBuffer?.length ?: 0,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "currentFieldLen=$currentLength isSequentialDel=$isSequentialDeletion"
        )

        if (currentLength > charCounter && charCounter > 0) {
            cancelPendingEmpty()
            val diff = currentLength - charCounter
            val inserted = textBefore?.takeLast(diff)?.toString() ?: ""
            if (inserted.isNotEmpty()) {
                onExternalInsert(context, inserted)
            }
        } else if (currentLength > 0) {
            cancelPendingEmpty()
            sawSelectionDelete = false
        } else if (currentLength == 0 && charCounter > 0) {
            if (lastActionWasSubmit) {
                cancelPendingEmpty()
                closeSession(context, STATUS_SENT)
            } else if (emptyCheckDelayMs <= 0L) {
                liveState = LiveState.PENDING_EMPTY
                triggerPendingEmptyCheck(context)
            } else {
                // Step 0.d / Round 2 item 1: production path for delayed empty check
                liveState = LiveState.PENDING_EMPTY
                saveCheckpoint(context, STATUS_DRAFT)
                pendingEmptyJob?.cancel()
                pendingEmptyJob = scope.launch(ioDispatcher) {
                    delay(emptyCheckDelayMs)
                    withContext(mainDispatcher) {
                        synchronized(engineLock) {
                            if (!isRecordingActive || liveState != LiveState.PENDING_EMPTY || charCounter == 0) {
                                return@withContext
                            }
                            triggerPendingEmptyCheck(context)
                        }
                    }
                }
            }
        }
    }

    /**
     * Replaces `deleteCount` code units before the cursor with `newText`.
     * Used for suggestion taps, space autocorrect, autocorrect reversal, double-space period, and Telex.
     */
    fun onReplaceBeforeCursor(context: Context, deleteCount: Int, newText: String) {
        if (!isRecordingActive) return
        cancelPendingEmpty()

        val buf = workingBuffer ?: StringBuilder().also { workingBuffer = it }
        val now = System.currentTimeMillis()
        lastKeystrokeTimestamp = now
        lastActionWasSubmit = false

        if (deleteCount > 0 && liveCharIndices.isNotEmpty()) {
            val numToDelete = deleteCount.coerceAtMost(liveCharIndices.size)
            val removedIndices = ArrayList<Int>()
            repeat(numToDelete) {
                removedIndices.add(liveCharIndices.removeAt(liveCharIndices.size - 1))
            }
            removedIndices.reverse()

            val deletedChars = StringBuilder()
            for (idx in removedIndices) {
                if (idx in 0 until buf.length) {
                    deletedChars.append(buf[idx])
                }
            }
            val startPos = removedIndices.firstOrNull() ?: (buf.length - numToDelete).coerceAtLeast(0)
            pendingDeletions.add(
                PendingDeletion(
                    char_or_word = deletedChars.toString(),
                    position = startPos,
                    deleted_at = now
                )
            )
            charCounter = (charCounter - numToDelete).coerceAtLeast(0)
            cursorPosition = (cursorPosition - numToDelete).coerceAtLeast(0)
        }

        if (newText.isNotEmpty()) {
            val insertStart = buf.length
            buf.append(newText)
            for (i in 0 until newText.length) {
                liveCharIndices.add(insertStart + i)
            }
            charCounter += newText.length
            cursorPosition = buf.length
        }
        sawSelectionDelete = false

        IhDebug.log(
            event = "onReplaceBeforeCursor",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = buf.length,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "deleteCount=$deleteCount newTextLen=${newText.length}"
        )
    }

    /**
     * Reports that a highlighted selection of `deletedLength` was replaced with `newText` (or deleted if newText is empty).
     */
    fun onSelectionReplaced(context: Context, deletedLength: Int, newText: String) {
        if (!isRecordingActive) return
        cancelPendingEmpty()

        val buf = workingBuffer ?: StringBuilder().also { workingBuffer = it }
        val now = System.currentTimeMillis()
        sawSelectionDelete = true
        lastKeystrokeTimestamp = now
        lastActionWasSubmit = false

        if (deletedLength > 0 && liveCharIndices.isNotEmpty()) {
            val numToDelete = deletedLength.coerceAtMost(liveCharIndices.size)
            val removedIndices = ArrayList<Int>()
            repeat(numToDelete) {
                removedIndices.add(liveCharIndices.removeAt(liveCharIndices.size - 1))
            }
            removedIndices.reverse()

            val deletedChars = StringBuilder()
            for (idx in removedIndices) {
                if (idx in 0 until buf.length) {
                    deletedChars.append(buf[idx])
                }
            }
            val startPos = removedIndices.firstOrNull() ?: (buf.length - numToDelete).coerceAtLeast(0)
            pendingDeletions.add(
                PendingDeletion(
                    char_or_word = deletedChars.toString(),
                    position = startPos,
                    deleted_at = now
                )
            )
            charCounter = (charCounter - numToDelete).coerceAtLeast(0)
            cursorPosition = (cursorPosition - numToDelete).coerceAtLeast(0)
        }

        if (newText.isNotEmpty()) {
            val insertStart = buf.length
            buf.append(newText)
            for (i in 0 until newText.length) {
                liveCharIndices.add(insertStart + i)
            }
            charCounter += newText.length
            cursorPosition = buf.length
            sawSelectionDelete = false
        } else if (charCounter > 0) {
            // Partial selection delete: reset sawSelectionDelete
            sawSelectionDelete = false
        } else if (charCounter == 0) {
            // Full selection delete: all text deleted
            closeSession(context, STATUS_DISCARDED)
        }

        IhDebug.log(
            event = "onSelectionReplaced",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = buf.length,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "deletedLen=$deletedLength newTextLen=${newText.length}"
        )
    }

    fun onSelectionDeleted(context: Context, deletedLength: Int) {
        onSelectionReplaced(context, deletedLength, "")
    }

    /**
     * Records an external insert (such as system paste or autofill) where IME keystroke was bypassed.
     */
    fun onExternalInsert(context: Context, text: String) {
        if (!isRecordingActive || text.isEmpty()) return
        cancelPendingEmpty()

        val buf = workingBuffer ?: StringBuilder().also { workingBuffer = it }
        val insertStart = buf.length
        buf.append(text)
        for (i in 0 until text.length) {
            liveCharIndices.add(insertStart + i)
        }
        charCounter += text.length
        cursorPosition = buf.length
        lastKeystrokeTimestamp = System.currentTimeMillis()
        sawSelectionDelete = false

        IhDebug.log(
            event = "onExternalInsert",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = buf.length,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "len=${text.length}"
        )
    }

    /**
     * Triggers when user presses Enter or executes an IME editor action.
     * Step 0.c: Multi-line Enter inserts newline with explicit parentheses; single-line marks submit.
     */
    fun onActionOrEnter(context: Context, inputConnection: InputConnection?, attribute: EditorInfo? = null) {
        if (!isRecordingActive || charCounter == 0) return

        val inputType = attribute?.inputType ?: InputType.TYPE_NULL
        val isMultiLine = ((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) ||
                          ((inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0)

        if (isMultiLine) {
            onKeystroke("\n", context)
            return
        }

        lastActionTimestamp = System.currentTimeMillis()
        lastActionWasSubmit = true

        val textBefore = inputConnection?.getTextBeforeCursor(200, 0)
        val textAfter = inputConnection?.getTextAfterCursor(200, 0)
        val currentLength = (textBefore?.length ?: 0) + (textAfter?.length ?: 0)

        IhDebug.log(
            event = "onActionOrEnter",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = workingBuffer?.length ?: 0,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "fieldLen=$currentLength isMultiLine=$isMultiLine"
        )

        if (inputConnection != null && currentLength == 0) {
            cancelPendingEmpty()
            closeSession(context, STATUS_SENT)
        }
    }

    /**
     * Executes delayed empty check (Step 0.d / 3.4.a).
     */
    fun triggerPendingEmptyCheck(context: Context) {
        if (!isRecordingActive || liveState != LiveState.PENDING_EMPTY) return
        val status = if (sawSelectionDelete || isSequentialDeletion) {
            STATUS_DISCARDED
        } else {
            STATUS_CLEARED_UNKNOWN
        }
        closeSession(context, status)
    }

    /**
     * Handles keyboard dismissal / finish input.
     * Step 0.b: Saves draft row to database when pausing so text is never lost on process death.
     */
    fun onFinishInput(context: Context? = null) {
        if (!isRecordingActive) return

        IhDebug.log(
            event = "onFinishInput",
            sessionId = sessionStartTimestamp,
            charCounter = charCounter,
            bufferLen = workingBuffer?.length ?: 0,
            liveIndicesSize = liveCharIndices.size,
            deletionsSize = pendingDeletions.size,
            pkg = currentPackage,
            extra = "hasContext=${context != null} charCounter=$charCounter testMode=$isTestMode"
        )

        val buf = workingBuffer
        if (context != null && buf != null && buf.isNotEmpty()) {
            if (charCounter > 0 && (!isTestMode || testPauseSessionsOnFinish)) {
                // Step 0.b & Sub-phase 3.5: Mark session paused and persist draft checkpoint to DB
                isSessionPaused = true
                saveCheckpoint(context, STATUS_DRAFT)
                return
            } else {
                val status = if (lastActionWasSubmit || (lastActionTimestamp > 0 && lastActionTimestamp >= lastKeystrokeTimestamp)) {
                    STATUS_SENT
                } else {
                    STATUS_LEFT_FIELD
                }
                closeSession(context, status)
            }
        }

        resetSessionState()
        isRecordingActive = false
        workingBuffer = null
    }

    /**
     * Sub-phase 3.5 / Round 2 item 1: Saves a lightweight checkpoint draft row to Room,
     * maintaining one single row per session (no duplicates) and appending
     * new deletion events in batches. Takes snapshot under engineLock and delegates DB write to ioDispatcher.
     */
    fun saveCheckpoint(context: Context, status: String = STATUS_DRAFT) {
        val (sessionSnapshot, newDeletions) = synchronized(engineLock) {
            if (!isRecordingActive || charCounter == 0 || workingBuffer.isNullOrEmpty()) return
            val fullText = workingBuffer?.toString() ?: ""
            val now = System.currentTimeMillis()
            val session = InputHistorySession(
                id = currentSessionRowId,
                app_package = currentPackage,
                app_label = currentAppLabel,
                start_timestamp = sessionStartTimestamp,
                end_timestamp = now,
                status = status,
                final_text = fullText,
                initial_text = initialText
            )

            val dels = if (pendingDeletions.size > persistedDeletionsCount) {
                ArrayList(pendingDeletions.subList(persistedDeletionsCount, pendingDeletions.size))
            } else {
                emptyList()
            }

            lastCheckpointTimestamp = now
            lastCheckpointKeystrokeCount = charCounter
            Pair(session, dels)
        }

        onCheckpointSavedForTest?.invoke(sessionSnapshot, newDeletions)

        if (!isTestMode) {
            val appContext = context.applicationContext
            scope.launch(ioDispatcher) {
                try {
                    val db = InputHistoryDatabase.getInstance(appContext)
                    db.runInTransaction {
                        if (sessionSnapshot.id == 0L) {
                            val newId = db.inputHistoryDao().insertSession(sessionSnapshot)
                            synchronized(engineLock) {
                                if (currentSessionRowId == 0L) {
                                    currentSessionRowId = newId
                                }
                            }
                        } else {
                            db.inputHistoryDao().updateSession(sessionSnapshot)
                        }

                        if (newDeletions.isNotEmpty()) {
                            val targetId = sessionSnapshot.id.takeIf { it != 0L } ?: synchronized(engineLock) { currentSessionRowId }
                            val events = newDeletions.map {
                                DeletionEvent(
                                    session_id = targetId,
                                    char_or_word = it.char_or_word,
                                    position = it.position,
                                    deleted_at = it.deleted_at
                                )
                            }
                            db.deletionEventDao().insertAll(events)
                            synchronized(engineLock) {
                                persistedDeletionsCount += newDeletions.size
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e("IHDBG", "Error saving checkpoint: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun closeSession(context: Context, status: String) {
        val (sessionSnapshot, remainingDeletionsSnapshot, allDeletionsSnapshot) = synchronized(engineLock) {
            cancelPendingEmpty()
            val endTimestamp = System.currentTimeMillis()
            val fullText = workingBuffer?.toString() ?: ""
            val session = InputHistorySession(
                id = currentSessionRowId,
                app_package = currentPackage,
                app_label = currentAppLabel,
                start_timestamp = sessionStartTimestamp,
                end_timestamp = endTimestamp,
                status = status,
                final_text = fullText,
                initial_text = initialText
            )

            val remainingDeletions = if (pendingDeletions.size > persistedDeletionsCount) {
                ArrayList(pendingDeletions.subList(persistedDeletionsCount, pendingDeletions.size))
            } else {
                emptyList()
            }

            val allDeletions = ArrayList(pendingDeletions)

            // Reset session state for subsequent input in the same field
            charCounter = 0
            workingBuffer?.clear()
            liveCharIndices.clear()
            pendingDeletions.clear()
            currentSessionRowId = 0L
            persistedDeletionsCount = 0
            isSequentialDeletion = false
            sawSelectionDelete = false
            liveState = LiveState.ACTIVE
            lastActionWasSubmit = false
            lastActionTimestamp = 0L
            lastKeystrokeTimestamp = 0L
            lastCheckpointKeystrokeCount = 0
            lastCheckpointTimestamp = 0L
            cursorPosition = 0
            sessionStartTimestamp = System.currentTimeMillis()

            Triple(session, remainingDeletions, allDeletions)
        }

        onSessionClosedForTest?.invoke(sessionSnapshot, allDeletionsSnapshot)

        IhDebug.log(
            event = "closeSession",
            sessionId = sessionSnapshot.start_timestamp,
            charCounter = 0,
            bufferLen = sessionSnapshot.final_text.length,
            liveIndicesSize = 0,
            deletionsSize = allDeletionsSnapshot.size,
            pkg = sessionSnapshot.app_package,
            extra = "status=$status rowId=${sessionSnapshot.id}"
        )

        if (!isTestMode) {
            val appContext = context.applicationContext
            val rowIdToUpdate = sessionSnapshot.id
            scope.launch(ioDispatcher) {
                try {
                    val db = InputHistoryDatabase.getInstance(appContext)
                    db.runInTransaction {
                        val finalRowId = if (rowIdToUpdate == 0L) {
                            db.inputHistoryDao().insertSession(sessionSnapshot)
                        } else {
                            db.inputHistoryDao().updateSession(sessionSnapshot)
                            rowIdToUpdate
                        }

                        if (remainingDeletionsSnapshot.isNotEmpty()) {
                            val events = remainingDeletionsSnapshot.map {
                                DeletionEvent(
                                    session_id = finalRowId,
                                    char_or_word = it.char_or_word,
                                    position = it.position,
                                    deleted_at = it.deleted_at
                                )
                            }
                            db.deletionEventDao().insertAll(events)
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e("IHDBG", "Error saving input history session: ${e.message}", e)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        resetSessionState()
        isRecordingActive = false
        workingBuffer = null
    }

    private fun resetSessionState() {
        synchronized(engineLock) {
            cancelPendingEmpty()
            charCounter = 0
            workingBuffer?.clear()
            liveCharIndices.clear()
            pendingDeletions.clear()
            currentSessionRowId = 0L
            persistedDeletionsCount = 0
            isSequentialDeletion = false
            sawSelectionDelete = false
            liveState = LiveState.ACTIVE
            lastActionWasSubmit = false
            lastActionTimestamp = 0L
            lastKeystrokeTimestamp = 0L
            lastCheckpointKeystrokeCount = 0
            lastCheckpointTimestamp = 0L
            initialText = ""
            composingText = ""
            cursorPosition = 0
            isSessionPaused = false
            sessionFieldKey = ""
        }
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
        if (packageName == "unknown" || packageName.isEmpty()) return "Unknown"
        return try {
            val pm = context.packageManager ?: return packageName
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    // Visible for test suite execution (T1-T18 matrix)
    var isTestMode: Boolean = false
    var testBlockedPackages: Set<String> = emptySet()
    var testRecordInputHistory: Boolean = true
    var testPauseSessionsOnFinish: Boolean = false
    var onSessionClosedForTest: ((session: InputHistorySession, deletions: List<PendingDeletion>) -> Unit)? = null
    var onCheckpointSavedForTest: ((session: InputHistorySession, deletions: List<PendingDeletion>) -> Unit)? = null

    fun getWorkingBufferForTest(): String? = workingBuffer?.toString()
    fun getLiveIndicesCountForTest(): Int = liveCharIndices.size
    fun getPendingDeletionsCountForTest(): Int = pendingDeletions.size
    fun isSequentialDeletionForTest(): Boolean = isSequentialDeletion

    fun resetForTests() {
        resetSessionState()
        isRecordingActive = false
        workingBuffer = null
        onSessionClosedForTest = null
        onCheckpointSavedForTest = null
        currentSessionRowId = 0L
        persistedDeletionsCount = 0
        emptyCheckDelayMs = 0L
        isTestMode = true
        testRecordInputHistory = true
        testPauseSessionsOnFinish = false
        testBlockedPackages = emptySet()
        mainDispatcher = Dispatchers.Main
        ioDispatcher = Dispatchers.IO
    }
}
