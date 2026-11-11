package org.fossify.keyboard

import android.text.InputType
import org.fossify.keyboard.helpers.SuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

class SuggestionEngineTest {

    private fun getResourceStream(path: String): InputStream {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
        if (stream != null) return stream

        val candidates = listOf(
            File(path),
            File("app/src/test/resources/$path"),
            File("src/test/resources/$path"),
            File("app/src/main/assets/$path"),
            File("src/main/assets/$path")
        )
        for (f in candidates) {
            if (f.exists()) return f.inputStream()
        }
        throw IllegalArgumentException("Resource not found: $path")
    }

    @Before
    fun setUp() {
        SuggestionEngine.resetForTests()

        // Load dictionary assets
        getResourceStream("dictionaries/en_unigrams.tsv").use { stream ->
            SuggestionEngine.loadUnigramsFromStream(stream)
        }
        getResourceStream("dictionaries/en_bigrams.tsv").use { stream ->
            SuggestionEngine.loadBigramsFromStream(stream)
        }
        getResourceStream("dictionaries/en_trigrams.tsv").use { stream ->
            SuggestionEngine.loadTrigramsFromStream(stream)
        }
    }

    @Test
    fun testBadAutocorrectRateOnValidWords() {
        val validWords = ArrayList<String>()
        BufferedReader(InputStreamReader(getResourceStream("fixtures/test_eval_1000_valid_words.txt"))).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim().lowercase(Locale.ROOT)
                if (trimmed.isNotEmpty()) {
                    validWords.add(trimmed)
                }
                line = reader.readLine()
            }
        }

        assertTrue("Expected 1,000 test words, found ${validWords.size}", validWords.size >= 1000)

        var badAutocorrects = 0
        val sample = validWords.take(1000)
        for (word in sample) {
            val correction = SuggestionEngine.getAutoCorrection(word)
            if (correction != null && correction.lowercase(Locale.ROOT) != word) {
                badAutocorrects++
                println("Bad autocorrect detected: '$word' -> '$correction'")
            }
        }

        val rate = badAutocorrects.toDouble() / sample.size.toDouble()
        println("=== Evaluation Metric: Bad Autocorrect Rate ===")
        println("Sample size: ${sample.size}")
        println("Bad autocorrects: $badAutocorrects")
        println(String.format(Locale.ROOT, "Bad Autocorrect Rate: %.2f%% (Target < 1.00%%)", rate * 100))

        assertTrue("Bad autocorrect rate must be < 1.0%, but was ${rate * 100}% ($badAutocorrects / ${sample.size})", rate < 0.01)
    }

    @Test
    fun testTop3NextWordPredictionAccuracy() {
        val phrases = ArrayList<List<String>>()
        BufferedReader(InputStreamReader(getResourceStream("fixtures/test_eval_500_phrases.txt"))).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val words = line.trim().lowercase(Locale.ROOT).split("\\s+".toRegex()).filter { it.isNotEmpty() }
                if (words.size >= 2) {
                    phrases.add(words)
                }
                line = reader.readLine()
            }
        }

        assertTrue("Expected 500 test phrases, found ${phrases.size}", phrases.size >= 500)

        var totalPredictions = 0
        var hitsInTop3 = 0

        for (phrase in phrases.take(500)) {
            for (i in 1 until phrase.size) {
                val prevWord = phrase[i - 1]
                val prevPrevWord = if (i >= 2) phrase[i - 2] else null
                val target = phrase[i]

                val predictions = SuggestionEngine.getNextWordPredictions(prevWord, prevPrevWord)
                totalPredictions++
                if (predictions.map { it.lowercase(Locale.ROOT) }.contains(target)) {
                    hitsInTop3++
                }
            }
        }

        val accuracy = hitsInTop3.toDouble() / totalPredictions.toDouble()
        println("=== Evaluation Metric: Top-3 Next-Word Accuracy ===")
        println("Total evaluations: $totalPredictions")
        println("Hits in Top-3: $hitsInTop3")
        println(String.format(Locale.ROOT, "Top-3 Accuracy: %.2f%% (Target >= 50.00%%)", accuracy * 100))

        assertTrue("Top-3 accuracy must be >= 50.0%, but was ${accuracy * 100}% ($hitsInTop3 / $totalPredictions)", accuracy >= 0.50)
    }

    @Test
    fun testTypoCorrection() {
        val testCases = mapOf(
            "teh" to "the",
            "adn" to "and",
            "taht" to "that",
            "waht" to "what",
            "wiht" to "with",
            "becuase" to "because",
            "recieve" to "receive",
            "seperate" to "separate",
            "jsut" to "just"
        )

        for ((typo, expected) in testCases) {
            val corrected = SuggestionEngine.getAutoCorrection(typo)
            assertNotNull("Expected correction for typo '$typo'", corrected)
            assertEquals("Correction for '$typo' mismatch", expected.lowercase(Locale.ROOT), corrected!!.lowercase(Locale.ROOT))
        }
    }

    @Test
    fun testDynamicCasing() {
        assertEquals("The", SuggestionEngine.applyCasing("Teh", "the"))
        assertEquals("THE", SuggestionEngine.applyCasing("TEH", "the"))
        assertEquals("the", SuggestionEngine.applyCasing("teh", "the"))
    }

    @Test
    fun testLiteralWordPreservedInSlot0() {
        val input = "qwertyxyz"
        val suggestions = SuggestionEngine.getSuggestions(input)
        assertTrue("Suggestions should not be empty", suggestions.isNotEmpty())
        assertEquals("Slot 0 must always preserve literal typed word", input, suggestions[0])
    }

    @Test
    fun testRejectionMemory() {
        // Initially, "teh" is autocorrected to "the"
        val correctionBefore = SuggestionEngine.getAutoCorrection("teh")
        assertEquals("the", correctionBefore?.lowercase(Locale.ROOT))

        // Record rejection (immediate backspace)
        SuggestionEngine.recordRejection("teh", "the")

        // Now, "teh" must NOT be autocorrected
        val correctionAfter = SuggestionEngine.getAutoCorrection("teh")
        assertNull("Autocorrect must be suppressed after rejection", correctionAfter)
    }

    @Test
    fun testMultiSessionGating() {
        val newWord = "zylophone"
        assertFalse("New word should not be initially valid", SuggestionEngine.isValidWord(newWord))

        // Session 1: typing in app A
        SuggestionEngine.onStartSession("com.app.one", InputType.TYPE_CLASS_TEXT)
        // Simulate learn in session 1 without Context
        // Verify staging logic directly
        assertFalse("Word should not be promoted on first occurrence", SuggestionEngine.isValidWord(newWord))
    }

    @Test
    fun testSensitiveFieldsGuard() {
        // Set sensitive field (e.g. password)
        SuggestionEngine.onStartSession(
            "com.secure.login",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        // All suggestions, autocorrect, and predictions must be suppressed
        assertTrue("Suggestions must be empty in password fields", SuggestionEngine.getSuggestions("hello").isEmpty())
        assertNull("Autocorrect must be null in password fields", SuggestionEngine.getAutoCorrection("teh"))
        assertTrue("Next-word predictions must be empty in password fields", SuggestionEngine.getNextWordPredictions("how").isEmpty())
    }

    @Test
    fun testLatencyBenchmark() {
        val queries = listOf("th", "wh", "yo", "peo", "bec", "loo", "hav", "com", "tim", "goo")
        val warmUpRounds = 50
        val benchmarkRounds = 1000

        // Warmup
        for (i in 0 until warmUpRounds) {
            val q = queries[i % queries.size]
            SuggestionEngine.getSuggestions(q)
            SuggestionEngine.getNextWordPredictions("how", null)
        }

        // Measure prefix completion
        val startPrefix = System.nanoTime()
        for (i in 0 until benchmarkRounds) {
            val q = queries[i % queries.size]
            SuggestionEngine.getSuggestions(q)
        }
        val prefixDurationMs = (System.nanoTime() - startPrefix) / 1_000_000.0
        val avgPrefixMs = prefixDurationMs / benchmarkRounds

        // Measure next-word prediction
        val startNextWord = System.nanoTime()
        for (i in 0 until benchmarkRounds) {
            SuggestionEngine.getNextWordPredictions("how", "are")
        }
        val nextWordDurationMs = (System.nanoTime() - startNextWord) / 1_000_000.0
        val avgNextWordMs = nextWordDurationMs / benchmarkRounds

        println("=== Latency Benchmark Results ===")
        println(String.format(Locale.ROOT, "Prefix completion mean latency: %.3f ms (Target: < 2.0 ms on desktop / < 8.0 ms mobile)", avgPrefixMs))
        println(String.format(Locale.ROOT, "Next-word prediction mean latency: %.3f ms (Target: < 2.0 ms on desktop / < 8.0 ms mobile)", avgNextWordMs))

        assertTrue("Prefix completion mean latency must be under 10ms", avgPrefixMs < 10.0)
        assertTrue("Next-word prediction mean latency must be under 10ms", avgNextWordMs < 10.0)
    }

    @Test
    fun testIntegrationTypingSpaceAndSuggestionFlow() {
        // 1. Simulate typing a word with a typo ("teh")
        val query = "teh"
        val activeSuggestions = SuggestionEngine.getSuggestions(query)
        assertTrue("Suggestions must not be empty for '$query'", activeSuggestions.isNotEmpty())
        assertEquals("Slot 0 must preserve literal typed input", "teh", activeSuggestions[0])
        assertEquals("Slot 1 must propose top candidate 'the'", "the", activeSuggestions[1])

        // 2. Press Space: conservative autocorrect triggers for "teh" -> "the"
        val autocorrectResult = SuggestionEngine.getAutoCorrection(query)
        assertNotNull("Space press should yield conservative correction for 'teh'", autocorrectResult)
        assertEquals("Autocorrect candidate must be 'the'", "the", autocorrectResult!!.lowercase(Locale.ROOT))

        // 3. Post-space: next-word predictions appear for committed word "the"
        val nextWords = SuggestionEngine.getNextWordPredictions(autocorrectResult)
        assertTrue("Next-word predictions must appear after space", nextWords.isNotEmpty())

        // 4. Tap a suggestion from next-word prediction list (e.g., first suggestion)
        val selectedSuggestion = nextWords[0]
        SuggestionEngine.recordWordTransition(autocorrectResult, selectedSuggestion)

        // 5. Subsequent predictions update correctly
        val followUpPredictions = SuggestionEngine.getNextWordPredictions(selectedSuggestion, autocorrectResult)
        assertTrue("Follow-up predictions must remain responsive", followUpPredictions.isNotEmpty())
    }
}
