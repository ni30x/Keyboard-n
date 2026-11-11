package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.InputHistorySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureSyncTest {

    private class TestContext : ContextWrapper(null) {
        override fun getPackageManager(): PackageManager? = null
        override fun getApplicationContext(): Context = this
    }

    private lateinit var mockContext: Context
    private val closedSessions = ArrayList<InputHistorySession>()
    private var lastDeletions = ArrayList<InputHistoryRecordingEngine.PendingDeletion>()

    @Before
    fun setUp() {
        InputHistoryRecordingEngine.resetForTests()
        closedSessions.clear()
        lastDeletions.clear()
        InputHistoryRecordingEngine.onSessionClosedForTest = { session, deletions ->
            closedSessions.add(session)
            lastDeletions.addAll(deletions)
        }
        mockContext = TestContext()
        InputHistoryRecordingEngine.onStartInput(mockContext, EditorInfo().apply {
            packageName = "com.chat.test"
            inputType = InputType.TYPE_CLASS_TEXT
        })
    }

    @Test
    fun testSuggestionTap_ReplacesPrefixAndMaintainsLedger() {
        // 1. User types "hel"
        "hel".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(3, InputHistoryRecordingEngine.charCounter)
        assertEquals("hel", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(3, InputHistoryRecordingEngine.getLiveIndicesCountForTest())

        // 2. User taps suggestion "hello "
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 3, newText = "hello ")

        // Final working buffer contains both authored prefix and replacement
        assertEquals("helhello ", InputHistoryRecordingEngine.getWorkingBufferForTest())
        // Live counter matches real field contents ("hello " = 6 chars)
        assertEquals(6, InputHistoryRecordingEngine.charCounter)
        assertEquals(6, InputHistoryRecordingEngine.getLiveIndicesCountForTest())

        // Deletion ledger has exactly 1 entry for the replaced prefix "hel" at index 0
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertEquals("helhello ", closedSessions[0].final_text)
        assertEquals(1, lastDeletions.size)
        assertEquals("hel", lastDeletions[0].char_or_word)
        assertEquals(0, lastDeletions[0].position)
    }

    @Test
    fun testSpaceAutocorrect_ReplacesTypoAndMaintainsLedger() {
        // User types typo "teh"
        "teh".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(3, InputHistoryRecordingEngine.charCounter)

        // Space autocorrect replaces "teh" with "the "
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 3, newText = "the ")

        assertEquals("tehthe ", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(4, InputHistoryRecordingEngine.charCounter)
        assertEquals(4, InputHistoryRecordingEngine.getLiveIndicesCountForTest())
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, lastDeletions.size)
        assertEquals("teh", lastDeletions[0].char_or_word)
        assertEquals(0, lastDeletions[0].position)
    }

    @Test
    fun testAutocorrectReversal_RestoresTypedWord() {
        // 1. "teh" autocorrected to "the "
        "teh".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 3, newText = "the ")

        // 2. User presses Backspace immediately: "the " (4 chars) is deleted and "teh" is restored
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 4, newText = "teh")

        assertEquals("tehthe teh", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(3, InputHistoryRecordingEngine.charCounter)
        assertEquals(3, InputHistoryRecordingEngine.getLiveIndicesCountForTest())
        // Two deletion entries: "teh" initially, then "the " on reversal
        assertEquals(2, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(2, lastDeletions.size)
        assertEquals("the ", lastDeletions[1].char_or_word)
    }

    @Test
    fun testDoubleSpacePeriod_ReplacesSpaceWithPeriodSpace() {
        // User types "hello "
        "hello ".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(6, InputHistoryRecordingEngine.charCounter)

        // Second space triggers double-space period: replaces last space (1 char) with ". "
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 1, newText = ". ")

        assertEquals("hello . ", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(7, InputHistoryRecordingEngine.charCounter)
        assertEquals(7, InputHistoryRecordingEngine.getLiveIndicesCountForTest())
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, lastDeletions.size)
        assertEquals(" ", lastDeletions[0].char_or_word)
        assertEquals(5, lastDeletions[0].position)
    }

    @Test
    fun testTypeOverSelection_ReplacesSelectedText() {
        // User types "cat dog"
        "cat dog".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(7, InputHistoryRecordingEngine.charCounter)

        // User highlights "dog" (3 chars) and types 's'
        InputHistoryRecordingEngine.onSelectionReplaced(mockContext, deletedLength = 3, newText = "s")

        assertEquals("cat dogs", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(5, InputHistoryRecordingEngine.charCounter)
        assertEquals(5, InputHistoryRecordingEngine.getLiveIndicesCountForTest())
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals("cat dogs", closedSessions[0].final_text)
        assertEquals("dog", lastDeletions[0].char_or_word)
        assertEquals(4, lastDeletions[0].position)
    }

    @Test
    fun testVietnameseTelex_ReplacesKeystrokeWithAccentedCharacter() {
        // User types 'a'
        InputHistoryRecordingEngine.onKeystroke('a')
        assertEquals(1, InputHistoryRecordingEngine.charCounter)

        // Second 'a' triggers Telex replacement: replaces previous 'a' with "â"
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 1, newText = "â")

        assertEquals("aâ", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(1, InputHistoryRecordingEngine.charCounter)
        assertEquals(1, InputHistoryRecordingEngine.getLiveIndicesCountForTest())
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals("aâ", closedSessions[0].final_text)
        assertEquals("a", lastDeletions[0].char_or_word)
        assertEquals(0, lastDeletions[0].position)
    }

    @Test
    fun testEmojiAndSurrogatePairs_SynchronizesAccurately() {
        // Plain text: "Hi " (3 code units)
        "Hi ".forEach { InputHistoryRecordingEngine.onKeystroke(it) }

        // Surrogate pair: party popper emoji "🎉" (2 UTF-16 code units: \uD83C\uDF89)
        val emoji = "🎉"
        assertEquals(2, emoji.length)
        InputHistoryRecordingEngine.onKeystroke(emoji)

        // Compound skin-tone emoji: "👍🏽" (4 UTF-16 code units: \uD83D\uDC4D\uD83C\uDFFD)
        val compoundEmoji = "👍🏽"
        assertEquals(4, compoundEmoji.length)
        InputHistoryRecordingEngine.onKeystroke(compoundEmoji)

        // Total live code units: 3 + 2 + 4 = 9
        assertEquals(9, InputHistoryRecordingEngine.charCounter)
        assertEquals(9, InputHistoryRecordingEngine.getLiveIndicesCountForTest())

        // Backspace deletes the compound emoji (4 code units)
        InputHistoryRecordingEngine.onWordDeleted(mockContext, 4)

        assertEquals(5, InputHistoryRecordingEngine.charCounter)
        assertEquals(5, InputHistoryRecordingEngine.getLiveIndicesCountForTest())
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals("Hi 🎉👍🏽", closedSessions[0].final_text)
        assertEquals(compoundEmoji, lastDeletions[0].char_or_word)
        assertEquals(5, lastDeletions[0].position)
    }

    @Test
    fun testNonLatinText_CyrillicArabicHindi() {
        // Cyrillic: "привет" (6 chars)
        val cyrillic = "привет"
        cyrillic.forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(6, InputHistoryRecordingEngine.charCounter)

        // Arabic: " مرحبا" (6 chars with space)
        val arabic = " مرحبا"
        arabic.forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(12, InputHistoryRecordingEngine.charCounter)

        // Hindi: " नमस्ते" (7 chars with space)
        val hindi = " नमस्ते"
        hindi.forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(19, InputHistoryRecordingEngine.charCounter)

        // Suggestion replacement on Hindi: replace "नमस्ते" with "धन्यवाद "
        InputHistoryRecordingEngine.onReplaceBeforeCursor(mockContext, deleteCount = 6, newText = "धन्यवाद ")

        assertEquals(21, InputHistoryRecordingEngine.charCounter)
        assertEquals(1, InputHistoryRecordingEngine.getPendingDeletionsCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals(1, closedSessions.size)
        assertTrue(closedSessions[0].final_text.contains("привет"))
        assertTrue(closedSessions[0].final_text.contains("مرحبا"))
        assertTrue(closedSessions[0].final_text.contains("धन्यवाद "))
    }

    @Test
    fun testExternalInsert_RecordsTextAndMaintainsBuffer() {
        "start ".forEach { InputHistoryRecordingEngine.onKeystroke(it) }
        assertEquals(6, InputHistoryRecordingEngine.charCounter)

        // External paste of "pasted content"
        InputHistoryRecordingEngine.onExternalInsert(mockContext, "pasted content")

        assertEquals("start pasted content", InputHistoryRecordingEngine.getWorkingBufferForTest())
        assertEquals(20, InputHistoryRecordingEngine.charCounter)
        assertEquals(20, InputHistoryRecordingEngine.getLiveIndicesCountForTest())

        InputHistoryRecordingEngine.onFinishInput(mockContext)
        assertEquals("start pasted content", closedSessions[0].final_text)
    }
}
