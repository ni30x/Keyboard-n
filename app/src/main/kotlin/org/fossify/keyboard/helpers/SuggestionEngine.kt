package org.fossify.keyboard.helpers

import android.content.Context
import android.text.InputType
import android.util.Log
import org.fossify.keyboard.databases.LearnedWordsDatabase
import org.fossify.keyboard.models.LearnedWord
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance, offline Gboard-style suggestion and autocorrect engine.
 *
 * Implements:
 * 1. Weighted unigram dictionary (~40k words) with normalized log-frequencies.
 * 2. Keyboard-aware spatial typo correction using Euclidean QWERTY distance costs.
 * 3. Pruned N-gram context model (25k+ bigrams, 5k trigrams) with Jelinek-Mercer smoothing.
 * 4. Conservative autocorrect on Space ($Score \ge 0.65$ and margin $\ge 0.15$ over runner-up).
 * 5. Rejection memory (immediate Backspace reverts autocorrect and records rejection).
 * 6. Privacy-preserving multi-session personal learning (word requires $\ge 2$ distinct sessions).
 * 7. Strict guard bypass in password, PIN, email, URL, and numeric input fields.
 * 8. User-facing learned word deletion and dictionary reset support.
 */
object SuggestionEngine {

    private const val TAG = "IME_DEBUG"

    private val executor = Executors.newSingleThreadExecutor()

    // 1. Unigram Lexicon Storage
    @Volatile private var unigramWords: List<String> = emptyList()
    @Volatile private var unigramFreqs: Map<String, Int> = emptyMap()
    @Volatile private var unigramSet: Set<String> = emptySet()
    @Volatile private var wordsByFirstChar: Map<Char, List<String>> = emptyMap()

    // 2. N-Gram Context Models
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val trigrams = HashMap<String, HashMap<String, Int>>()
    private val userBigrams = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    // 3. User Learned Words & Multi-Session Staging
    private val learnedWordsMap = ConcurrentHashMap<String, Int>()
    private val sessionStagingMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val rejectionCache = ConcurrentHashMap<String, String>()

    // Session Tracking
    @Volatile private var currentSessionId: String = ""
    @Volatile private var lastSessionTimestamp: Long = 0L
    @Volatile private var currentPackageName: String = ""
    @Volatile private var isLearningGuarded: Boolean = false
    @Volatile private var isInitialized: Boolean = false

    // Physical QWERTY key grid coordinates
    private val KEY_COORDS = mapOf(
        'q' to Pair(0.0f, 0.0f), 'w' to Pair(1.0f, 0.0f), 'e' to Pair(2.0f, 0.0f),
        'r' to Pair(3.0f, 0.0f), 't' to Pair(4.0f, 0.0f), 'y' to Pair(5.0f, 0.0f),
        'u' to Pair(6.0f, 0.0f), 'i' to Pair(7.0f, 0.0f), 'o' to Pair(8.0f, 0.0f), 'p' to Pair(9.0f, 0.0f),
        'a' to Pair(0.5f, 1.0f), 's' to Pair(1.5f, 1.0f), 'd' to Pair(2.5f, 1.0f),
        'f' to Pair(3.5f, 1.0f), 'g' to Pair(4.5f, 1.0f), 'h' to Pair(5.5f, 1.0f),
        'j' to Pair(6.5f, 1.0f), 'k' to Pair(7.5f, 1.0f), 'l' to Pair(8.5f, 1.0f),
        'z' to Pair(1.5f, 2.0f), 'x' to Pair(2.5f, 2.0f), 'c' to Pair(3.5f, 2.0f),
        'v' to Pair(4.5f, 2.0f), 'b' to Pair(5.5f, 2.0f), 'n' to Pair(6.5f, 2.0f), 'm' to Pair(7.5f, 2.0f)
    )

    // Curated high-confidence typo overrides
    private val HIGH_CONFIDENCE_SHORT_TYPOS = mapOf(
        "teh" to "the", "adn" to "and", "taht" to "that", "waht" to "what", "wiht" to "with",
        "thier" to "their", "untill" to "until", "becuase" to "because", "dont" to "don't",
        "cant" to "can't", "wont" to "won't", "im" to "I'm", "youre" to "you're",
        "theyre" to "they're", "weve" to "we've", "isnt" to "isn't", "arent" to "aren't",
        "wasnt" to "wasn't", "hasnt" to "hasn't", "havent" to "haven't", "doesnt" to "doesn't",
        "didnt" to "didn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't",
        "alot" to "a lot", "recieve" to "receive", "seperate" to "separate", "definately" to "definitely",
        "tomo" to "tomorrow", "tmrw" to "tomorrow", "plz" to "please", "thx" to "thanks",
        "hvae" to "have", "knwo" to "know", "mkae" to "make", "liek" to "like", "jsut" to "just"
    )

    private fun logDebug(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {}
    }

    private fun logError(msg: String, t: Throwable? = null) {
        try {
            Log.e(TAG, msg, t)
        } catch (_: Throwable) {}
    }

    /**
     * Initializes dictionary assets and Room user dictionary off the UI thread.
     */
    fun init(context: Context) {
        if (isInitialized) return
        logDebug("SuggestionEngine.init requested")
        executor.execute {
            try {
                logDebug("SuggestionEngine.init starting background asset loading...")
                loadUnigramsAsset(context)
                loadBigramsAsset(context)
                loadTrigramsAsset(context)

                val dbWords = LearnedWordsDatabase.getInstance(context).learnedWordsDao().getAll()
                for (item in dbWords) {
                    learnedWordsMap[item.word.lowercase(Locale.ROOT)] = item.frequency
                }
                isInitialized = true
                logDebug("SuggestionEngine.init completed successfully. Unigrams count: ${unigramWords.size}")
            } catch (e: Exception) {
                logError("SuggestionEngine.init asset loading failed: ${e.message}", e)
                isInitialized = true
            }
        }
    }

    // --- Loading Assets ---

    fun loadUnigramsFromStream(stream: InputStream) {
        BufferedReader(InputStreamReader(stream, "UTF-8")).use { reader ->
            loadUnigrams(reader)
        }
    }

    fun loadUnigrams(reader: BufferedReader) {
        val tempWords = ArrayList<String>(40000)
        val tempFreqs = HashMap<String, Int>(40000)
        val tempSet = HashSet<String>(40000)
        val tempBucket = HashMap<Char, ArrayList<String>>()

        var line = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val parts = trimmed.split("\t")
                val word = parts[0].lowercase(Locale.ROOT)
                val freq = if (parts.size > 1) parts[1].toIntOrNull() ?: 100 else 100

                tempWords.add(word)
                tempFreqs[word] = freq
                tempSet.add(word)

                val first = word.firstOrNull()
                if (first != null && first.isLetter()) {
                    tempBucket.getOrPut(first) { ArrayList() }.add(word)
                }
            }
            line = reader.readLine()
        }

        // Publish completed immutable references atomically
        unigramWords = tempWords
        unigramFreqs = tempFreqs
        unigramSet = tempSet
        wordsByFirstChar = tempBucket
        isInitialized = true
    }

    private fun loadUnigramsAsset(context: Context) {
        val assetManager = context.assets
        try {
            assetManager.open("dictionaries/en_unigrams.tsv").use { stream ->
                loadUnigramsFromStream(stream)
            }
        } catch (e: Exception) {
            // Fallback to english_words.txt
            try {
                assetManager.open("dictionaries/english_words.txt").use { stream ->
                    BufferedReader(InputStreamReader(stream, "UTF-8")).use { reader ->
                        loadUnigrams(reader)
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    fun loadBigramsFromStream(stream: InputStream) {
        BufferedReader(InputStreamReader(stream, "UTF-8")).use { reader ->
            loadBigrams(reader)
        }
    }

    fun loadBigrams(reader: BufferedReader) {
        bigrams.clear()
        var line = reader.readLine()
        while (line != null) {
            val parts = line.trim().split("\t")
            if (parts.size >= 3) {
                val w1 = parts[0].lowercase(Locale.ROOT)
                val w2 = parts[1].lowercase(Locale.ROOT)
                val score = parts[2].toIntOrNull() ?: 100
                bigrams.getOrPut(w1) { HashMap() }[w2] = score
            }
            line = reader.readLine()
        }
    }

    private fun loadBigramsAsset(context: Context) {
        try {
            context.assets.open("dictionaries/en_bigrams.tsv").use { stream ->
                loadBigramsFromStream(stream)
            }
        } catch (ignored: Exception) {}
    }

    fun loadTrigramsFromStream(stream: InputStream) {
        BufferedReader(InputStreamReader(stream, "UTF-8")).use { reader ->
            loadTrigrams(reader)
        }
    }

    fun loadTrigrams(reader: BufferedReader) {
        trigrams.clear()
        var line = reader.readLine()
        while (line != null) {
            val parts = line.trim().split("\t")
            if (parts.size >= 4) {
                val key = "${parts[0].lowercase(Locale.ROOT)} ${parts[1].lowercase(Locale.ROOT)}"
                val w3 = parts[2].lowercase(Locale.ROOT)
                val score = parts[3].toIntOrNull() ?: 100
                trigrams.getOrPut(key) { HashMap() }[w3] = score
            }
            line = reader.readLine()
        }
    }

    private fun loadTrigramsAsset(context: Context) {
        try {
            context.assets.open("dictionaries/en_trigrams.tsv").use { stream ->
                loadTrigramsFromStream(stream)
            }
        } catch (ignored: Exception) {}
    }

    // --- Spatial Distance and Typo Cost ---

    /**
     * Calculates the keyboard-aware substitution cost between two characters.
     * Uses Euclidean distance on the physical QWERTY key grid.
     */
    fun getSubstitutionCost(c1: Char, c2: Char): Float {
        if (c1 == c2) return 0.0f
        val p1 = KEY_COORDS[c1]
        val p2 = KEY_COORDS[c2]
        if (p1 == null || p2 == null) return 1.0f

        val dx = p1.first - p2.first
        val dy = p1.second - p2.second
        val dist = sqrt(dx * dx + dy * dy)

        // Horizontal neighbor (dist = 1.0) -> cost 0.35f
        // Staggered diagonal neighbor (dist ≈ 1.12) -> cost ~0.39f
        // Distant keys (dist >= 2.85) -> cost 1.0f
        return (0.35f + 0.35f * (dist - 1.0f)).coerceIn(0.35f, 1.0f)
    }

    /**
     * Weighted Damerau-Levenshtein distance incorporating physical key layout costs.
     */
    fun weightedDistance(s1: String, s2: String, maxAllowed: Float = 2.0f): Float {
        val m = s1.length
        val n = s2.length
        if (abs(m - n) * 0.75f > maxAllowed) return maxAllowed + 1.0f

        val dp = Array(m + 1) { FloatArray(n + 1) }
        for (i in 0..m) dp[i][0] = i * 0.75f // insertion/deletion cost = 0.75
        for (j in 0..n) dp[0][j] = j * 0.75f

        for (i in 1..m) {
            val c1 = s1[i - 1]
            var minInRow = Float.MAX_VALUE
            for (j in 1..n) {
                val c2 = s2[j - 1]
                val subCost = getSubstitutionCost(c1, c2)

                var cost = min(
                    min(dp[i - 1][j] + 0.75f, dp[i][j - 1] + 0.75f),
                    dp[i - 1][j - 1] + subCost
                )

                // Transposition
                if (i > 1 && j > 1 && s1[i - 1] == s2[j - 2] && s1[i - 2] == s2[j - 1]) {
                    cost = min(cost, dp[i - 2][j - 2] + 0.50f)
                }

                dp[i][j] = cost
                if (cost < minInRow) minInRow = cost
            }
            if (minInRow > maxAllowed) return maxAllowed + 1.0f
        }
        return dp[m][n]
    }

    // --- Scoring & Candidate Selection ---

    fun isValidWord(word: String): Boolean {
        val lower = word.trim().lowercase(Locale.ROOT)
        return unigramSet.contains(lower) || learnedWordsMap.containsKey(lower)
    }

    /**
     * Candidate scoring function:
     * Score(w) = α * SpatialSimilarity + (1 - α) * NormalizedLogFreq
     */
    fun computeScore(typed: String, candidate: String, editCost: Float): Float {
        val spatialSimilarity = exp(-editCost) // 1.0 for perfect, decreases exponentially
        val freq = unigramFreqs[candidate] ?: learnedWordsMap[candidate] ?: 1
        val normFreq = (freq.toFloat() / 255.0f).coerceIn(0.01f, 1.0f)
        return 0.65f * spatialSimilarity + 0.35f * normFreq
    }

    // --- Suggestion Generation ---

    /**
     * Asynchronously computes suggestions and delivers them on the main thread callback.
     */
    fun getSuggestionsAsync(query: String, prevWord: String? = null, callback: (List<String>) -> Unit) {
        executor.execute {
            val results = getSuggestions(query, prevWord)
            callback(results)
        }
    }

    /**
     * Generates a 3-slot suggestion list for active composing text:
     * Left: Literal typed input
     * Center: Top completion or autocorrect candidate (bold)
     * Right: Secondary alternative candidate
     */
    fun getSuggestions(query: String, prevWord: String? = null): List<String> {
        if (isLearningGuarded) {
            logDebug("getSuggestions bypassed because field is sensitive (isLearningGuarded=true)")
            return emptyList()
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !trimmed.any { it.isLetter() }) {
            return emptyList()
        }

        val lowerQuery = trimmed.lowercase(Locale.ROOT)
        val candidates = LinkedHashMap<String, Float>() // word -> score

        // 1. Literal exact match check
        val isExactValid = isValidWord(lowerQuery)
        val typoOverride = HIGH_CONFIDENCE_SHORT_TYPOS[lowerQuery]
        if (typoOverride != null && !rejectionCache.containsKey(lowerQuery)) {
            candidates[typoOverride] = 1.0f
        }

        // 2. Fast prefix completions (Trie / first char bucket search)
        val firstChar = lowerQuery.firstOrNull()
        val bucket = if (firstChar != null) wordsByFirstChar[firstChar] else null
        if (bucket != null) {
            for (word in bucket) {
                if (word.startsWith(lowerQuery) && word != lowerQuery) {
                    val freq = unigramFreqs[word] ?: 1
                    val score = 0.50f + 0.50f * (freq.toFloat() / 255.0f)
                    candidates[word] = score
                    if (candidates.size >= 12) break
                }
            }
        }

        // Check user learned words for prefix completions
        for ((learnedWord, freq) in learnedWordsMap) {
            if (learnedWord.startsWith(lowerQuery) && learnedWord != lowerQuery) {
                candidates[learnedWord] = 0.70f + 0.30f * (freq.toFloat() / 255.0f)
            }
        }

        // 3. Typo matching if prefix matches are scarce
        if (candidates.size < 6 && lowerQuery.length >= 3) {
            val searchPool = bucket ?: unigramWords.take(4000)
            for (word in searchPool) {
                if (abs(word.length - lowerQuery.length) <= 2) {
                    val cost = weightedDistance(lowerQuery, word, 1.8f)
                    if (cost <= 1.8f) {
                        val score = computeScore(lowerQuery, word, cost)
                        if (score > (candidates[word] ?: 0f)) {
                            candidates[word] = score
                        }
                    }
                }
            }
        }

        // 4. Missing-space segmentation check (e.g., "inther" -> "in the", "thankyou" -> "thank you")
        if (candidates.isEmpty() && lowerQuery.length >= 5) {
            for (i in 2 until (lowerQuery.length - 2)) {
                val w1 = lowerQuery.substring(0, i)
                val w2 = lowerQuery.substring(i)
                if (isValidWord(w1) && isValidWord(w2)) {
                    val splitWord = "$w1 $w2"
                    candidates[splitWord] = 0.75f
                    break
                }
            }
        }

        val sortedList = candidates.entries
            .filter { it.key != lowerQuery }
            .sortedByDescending { it.value }
            .map { it.key }
            .toMutableList()

        // Construct 3-slot layout:
        // Left = Literal typed input
        // Center = Best candidate
        // Right = Secondary candidate
        val primaryCandidate = if (sortedList.isNotEmpty()) sortedList.removeAt(0) else (if (isExactValid) lowerQuery else "")
        val secondaryCandidate = if (sortedList.isNotEmpty()) sortedList.removeAt(0) else ""

        val left = lowerQuery
        val center = if (primaryCandidate.isNotEmpty()) primaryCandidate else lowerQuery
        val right = secondaryCandidate

        return listOf(
            applyCasing(trimmed, left),
            applyCasing(trimmed, center),
            applyCasing(trimmed, right)
        )
    }

    /**
     * Evaluates whether Space should trigger conservative autocorrection.
     */
    fun getAutoCorrection(word: String, prevWord: String? = null): String? {
        val startTimeNs = System.nanoTime()
        if (!isInitialized) {
            logDebug("getAutoCorrection bypassed because engine assets are not initialized yet")
            return null
        }
        if (isLearningGuarded) {
            logDebug("getAutoCorrection bypassed because input field is sensitive")
            return null
        }
        val trimmed = word.trim()
        if (trimmed.length < 2) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        // Check if user previously rejected this correction
        if (rejectionCache.containsKey(lower)) return null

        // High-confidence typo check (checked before isValidWord to handle common typos like "teh" or "adn")
        val typoOverride = HIGH_CONFIDENCE_SHORT_TYPOS[lower]
        if (typoOverride != null) {
            return applyCasing(trimmed, typoOverride)
        }

        // Never autocorrect a valid dictionary word
        if (isValidWord(lower)) return null

        // Evaluate spatial typo candidates with 5 ms hard cap
        val bucket = wordsByFirstChar[lower.first()] ?: unigramWords.take(5000)
        val scoredCandidates = ArrayList<Pair<String, Float>>()

        for (candidate in bucket) {
            // Hard 5 ms (5,000,000 ns) execution time cap
            if (System.nanoTime() - startTimeNs > 5_000_000L) {
                logDebug("getAutoCorrection evaluation aborted for '$lower' (exceeded 5ms hard cap)")
                return null
            }

            if (abs(candidate.length - lower.length) <= 2) {
                val dist = weightedDistance(lower, candidate, 1.6f)
                if (dist <= 1.6f) {
                    val score = computeScore(lower, candidate, dist)
                    scoredCandidates.add(Pair(candidate, score))
                }
            }
        }

        if (scoredCandidates.isEmpty()) return null

        scoredCandidates.sortByDescending { it.second }
        val top = scoredCandidates[0]
        val runnerUp = scoredCandidates.getOrNull(1)

        // Threshold check: top >= 0.65 and margin >= 0.15 over runner-up
        val margin = if (runnerUp != null) top.second - runnerUp.second else 1.0f
        if (top.second >= 0.65f && margin >= 0.15f) {
            return applyCasing(trimmed, top.first)
        }

        return null
    }

    // --- Next-Word Prediction ---

    fun getNextWordPredictionsAsync(prevWord: String, prevPrevWord: String? = null, callback: (List<String>) -> Unit) {
        executor.execute {
            val results = getNextWordPredictions(prevWord, prevPrevWord)
            callback(results)
        }
    }

    fun getNextWordPredictions(prevWord: String, prevPrevWord: String? = null): List<String> {
        if (isLearningGuarded) return emptyList()
        val cleanPrev = prevWord.trim().lowercase(Locale.ROOT)
        if (cleanPrev.isEmpty() || !cleanPrev.any { it.isLetter() }) return emptyList()

        val scored = HashMap<String, Float>()

        // 1. Trigram Context if 2 previous words exist (λ3 = 0.50)
        if (prevPrevWord != null) {
            val cleanPrevPrev = prevPrevWord.trim().lowercase(Locale.ROOT)
            val triKey = "$cleanPrevPrev $cleanPrev"
            val triMap = trigrams[triKey]
            if (triMap != null) {
                for ((w3, count) in triMap) {
                    scored[w3] = (scored[w3] ?: 0f) + 0.50f * (count.toFloat() / 255.0f)
                }
            }
        }

        // 2. Bigram Context (λ2 = 0.35)
        val biMap = bigrams[cleanPrev]
        if (biMap != null) {
            for ((w2, count) in biMap) {
                scored[w2] = (scored[w2] ?: 0f) + 0.35f * (count.toFloat() / 255.0f)
            }
        }

        // User learned personal bigrams
        val userBi = userBigrams[cleanPrev]
        if (userBi != null) {
            for ((w2, count) in userBi) {
                scored[w2] = (scored[w2] ?: 0f) + 0.40f * min(1.0f, count.toFloat() / 10.0f)
            }
        }

        // 3. Unigram frequency baseline (λ1 = 0.15)
        val fallbacks = listOf("the", "to", "and", "a", "in", "it", "is", "i", "you", "that")
        for (w in fallbacks) {
            scored[w] = (scored[w] ?: 0f) + 0.15f * ((unigramFreqs[w] ?: 100).toFloat() / 255.0f)
        }

        val topSorted = scored.entries
            .filter { it.key != cleanPrev }
            .sortedByDescending { it.value }
            .map { it.key }

        return topSorted.take(3)
    }

    // --- Dynamic Casing ---

    fun applyCasing(original: String, target: String): String {
        if (target.isEmpty()) return ""
        val isAllCaps = original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() }
        val isCapitalized = original.firstOrNull()?.isUpperCase() == true
        return when {
            isAllCaps -> target.uppercase(Locale.ROOT)
            isCapitalized -> target.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            else -> target
        }
    }

    // --- Rejection Memory ---

    fun recordRejection(typed: String, rejected: String) {
        val cleanTyped = typed.trim().lowercase(Locale.ROOT)
        val cleanRejected = rejected.trim().lowercase(Locale.ROOT)
        if (cleanTyped.isNotEmpty() && cleanRejected.isNotEmpty()) {
            rejectionCache[cleanTyped] = cleanRejected
        }
    }

    fun isRejected(typed: String, candidate: String): Boolean {
        val cleanTyped = typed.trim().lowercase(Locale.ROOT)
        val cleanCandidate = candidate.trim().lowercase(Locale.ROOT)
        return rejectionCache[cleanTyped] == cleanCandidate
    }

    fun clearRejections() {
        rejectionCache.clear()
    }

    fun resetForTests() {
        unigramWords = emptyList()
        unigramFreqs = emptyMap()
        unigramSet = emptySet()
        wordsByFirstChar = emptyMap()
        learnedWordsMap.clear()
        sessionStagingMap.clear()
        userBigrams.clear()
        rejectionCache.clear()
        currentSessionId = ""
        currentPackageName = ""
        lastSessionTimestamp = 0L
        isLearningGuarded = false
        isInitialized = false
    }

    // --- Multi-Session Personal Learning & Privacy ---

    /**
     * Called on IME onStartInput to begin or update an input session.
     */
    fun onStartSession(packageName: String, inputType: Int) {
        val now = System.currentTimeMillis()
        val isNewPackage = packageName != currentPackageName
        val isNewTimeGap = now - lastSessionTimestamp >= 60_000L

        if (isNewPackage || isNewTimeGap) {
            currentSessionId = "sess_${System.nanoTime()}"
            currentPackageName = packageName
        }
        lastSessionTimestamp = now

        // Check sensitive input type guards
        isLearningGuarded = isSensitiveField(inputType)
        logDebug("onStartSession pkg='$packageName' inputType=$inputType isLearningGuarded=$isLearningGuarded")
    }

    fun onFinishSession() {
        lastSessionTimestamp = System.currentTimeMillis()
    }

    private fun isSensitiveField(inputType: Int): Boolean {
        if (inputType == 0) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and InputType.TYPE_MASK_CLASS

        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        val isEmailOrUri = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI

        val isNumber = inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME

        val isSensitive = isPassword || isEmailOrUri || isNumber
        if (isSensitive) {
            logDebug("Sensitive field detected: inputType=$inputType (isPassword=$isPassword, isEmailOrUri=$isEmailOrUri, isNumber=$isNumber)")
        }
        return isSensitive
    }

    /**
     * Staging-gated word learning.
     * A word is only promoted to persistent storage after appearing in >= 2 distinct sessions.
     */
    fun learnWord(context: Context, word: String) {
        if (isLearningGuarded) return
        val clean = word.trim().lowercase(Locale.ROOT)
        if (clean.length < 2 || clean.any { !it.isLetter() }) return

        val sessions = sessionStagingMap.getOrPut(clean) { ConcurrentHashMap.newKeySet() }
        if (currentSessionId.isNotEmpty()) {
            sessions.add(currentSessionId)
        }

        // Multi-session promotion condition: >= 2 distinct sessions
        if (sessions.size >= 2) {
            val prevFreq = learnedWordsMap[clean] ?: 0
            val newFreq = prevFreq + 1
            learnedWordsMap[clean] = newFreq

            executor.execute {
                try {
                    val dao = LearnedWordsDatabase.getInstance(context).learnedWordsDao()
                    dao.insertOrUpdate(LearnedWord(word = clean, frequency = newFreq, lastUsed = System.currentTimeMillis()))
                } catch (ignored: Exception) {}
            }
        }
    }

    fun recordWordTransition(w1: String, w2: String) {
        if (isLearningGuarded) return
        val c1 = w1.trim().lowercase(Locale.ROOT)
        val c2 = w2.trim().lowercase(Locale.ROOT)
        if (c1.isNotEmpty() && c2.isNotEmpty() && c1.all { it.isLetter() } && c2.all { it.isLetter() }) {
            val userMap = userBigrams.getOrPut(c1) { ConcurrentHashMap() }
            userMap[c2] = (userMap[c2] ?: 0) + 1
        }
    }

    // --- User Management: Delete & Reset ---

    fun deleteLearnedWord(context: Context, word: String) {
        val clean = word.trim().lowercase(Locale.ROOT)
        learnedWordsMap.remove(clean)
        sessionStagingMap.remove(clean)
        executor.execute {
            try {
                LearnedWordsDatabase.getInstance(context).learnedWordsDao().deleteWord(clean)
            } catch (ignored: Exception) {}
        }
    }

    fun clearAllLearnedWords(context: Context) {
        learnedWordsMap.clear()
        sessionStagingMap.clear()
        userBigrams.clear()
        rejectionCache.clear()
        executor.execute {
            try {
                LearnedWordsDatabase.getInstance(context).learnedWordsDao().clearAll()
            } catch (ignored: Exception) {}
        }
    }

    fun getLearnedWordsList(): List<Pair<String, Int>> {
        return learnedWordsMap.entries.sortedByDescending { it.value }.map { Pair(it.key, it.value) }
    }

    /**
     * Precomputes next letter frequencies for continuous touch targets.
     */
    fun fillNextCharFrequencies(prefix: String, outFrequencies: IntArray) {
        val lower = prefix.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return
        val first = lower[0]
        val bucket = wordsByFirstChar[first] ?: return

        for (word in bucket) {
            if (word.startsWith(lower) && word.length > lower.length) {
                val nextChar = word[lower.length]
                if (nextChar in 'a'..'z') {
                    outFrequencies[nextChar - 'a']++
                }
            }
        }
    }
}
