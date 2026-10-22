package org.fossify.keyboard.helpers

import android.content.Context
import org.fossify.keyboard.databases.LearnedWordsDatabase
import org.fossify.keyboard.models.LearnedWord
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SuggestionEngine {

    private val executor = Executors.newSingleThreadExecutor()
    private val learnedWordsMap = ConcurrentHashMap<String, Int>()
    private val englishWords = ArrayList<String>()
    private val hinglishWords = ArrayList<String>()
    private val englishWordsSet = HashSet<String>()
    private val hinglishWordsSet = HashSet<String>()
    private val wordsByFirstChar = HashMap<Char, ArrayList<String>>()

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        executor.execute {
            try {
                loadBundledDictionary(context, "dictionaries/hinglish_words.txt", hinglishWords, hinglishWordsSet)
                loadBundledDictionary(context, "dictionaries/english_words.txt", englishWords, englishWordsSet)

                val dbWords = LearnedWordsDatabase.getInstance(context).learnedWordsDao().getAll()
                for (item in dbWords) {
                    learnedWordsMap[item.word.lowercase(Locale.ROOT)] = item.frequency
                }
                isInitialized = true
            } catch (ignored: Exception) {
            }
        }
    }

    private fun loadBundledDictionary(
        context: Context,
        assetPath: String,
        targetList: MutableList<String>,
        targetSet: MutableSet<String>
    ) {
        try {
            context.assets.open(assetPath).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    for (line in lines) {
                        val word = line.trim().lowercase(Locale.ROOT)
                        if (word.isNotEmpty() && targetSet.add(word)) {
                            targetList.add(word)
                            val first = word[0]
                            wordsByFirstChar.getOrPut(first) { ArrayList() }.add(word)
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    fun learnWord(context: Context, rawWord: String) {
        val word = rawWord.trim().lowercase(Locale.ROOT)
        if (word.length < 2 || word.length > 35 || !word.all { it.isLetter() || it == '\'' }) {
            return
        }

        val updatedFreq = (learnedWordsMap[word] ?: 0) + 1
        learnedWordsMap[word] = updatedFreq

        executor.execute {
            try {
                val dao = LearnedWordsDatabase.getInstance(context).learnedWordsDao()
                dao.insertOrUpdate(LearnedWord(word = word, frequency = updatedFreq, lastUsed = System.currentTimeMillis()))
            } catch (ignored: Exception) {
            }
        }
    }

    fun getSuggestions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !trimmed.any { it.isLetter() }) {
            return emptyList()
        }

        val lowerQuery = trimmed.lowercase(Locale.ROOT)
        val candidates = LinkedHashSet<String>()

        // 1. Exact match check
        val isLearned = learnedWordsMap.containsKey(lowerQuery)
        val isBundled = hinglishWordsSet.contains(lowerQuery) || englishWordsSet.contains(lowerQuery)
        val exactMatchExists = isLearned || isBundled

        if (exactMatchExists) {
            candidates.add(lowerQuery)
        }

        // 2. High priority: User learned words matching prefix (sorted by frequency desc)
        val prefixLearned = learnedWordsMap.entries
            .filter { it.key.startsWith(lowerQuery) && it.key != lowerQuery }
            .sortedByDescending { it.value }
            .map { it.key }
        for (w in prefixLearned) {
            if (candidates.size >= 6) break
            candidates.add(w)
        }

        // 3. Fast prefix matching using first character index
        val firstChar = lowerQuery[0]
        val bucket = wordsByFirstChar[firstChar]
        if (bucket != null && candidates.size < 6) {
            for (w in bucket) {
                if (w.startsWith(lowerQuery) && w != lowerQuery) {
                    candidates.add(w)
                    if (candidates.size >= 6) break
                }
            }
        }

        // 4. Fuzzy Spell Correction (Levenshtein Distance) for words with length >= 3
        if (candidates.size < 3 && lowerQuery.length >= 3) {
            val spellCorrections = findSpellCorrections(lowerQuery)
            for (corr in spellCorrections) {
                candidates.add(corr)
                if (candidates.size >= 6) break
            }
        }

        if (candidates.isEmpty()) {
            candidates.add(lowerQuery)
        }

        val uniquePool = candidates.toMutableList()

        // Select 3 distinct slots: Left, Center (Best), Right
        val center: String
        val left: String
        val right: String

        if (exactMatchExists) {
            center = lowerQuery
            uniquePool.remove(lowerQuery)
            left = if (uniquePool.isNotEmpty()) uniquePool.removeAt(0) else ""
            right = if (uniquePool.isNotEmpty()) uniquePool.removeAt(0) else ""
        } else {
            // Misspelled or prefix: Center is best suggestion/correction
            center = if (uniquePool.isNotEmpty()) uniquePool.removeAt(0) else lowerQuery
            // Left is literal query so user can keep literal spelling if desired
            left = if (center != lowerQuery) lowerQuery else (if (uniquePool.isNotEmpty()) uniquePool.removeAt(0) else "")
            uniquePool.remove(lowerQuery)
            // Right is third suggestion
            right = if (uniquePool.isNotEmpty()) uniquePool.removeAt(0) else ""
        }

        val top3 = listOf(left, center, right)

        // Match case: All caps or Title case
        val isAllCaps = trimmed.length > 1 && trimmed.all { it.isUpperCase() }
        val isCapitalized = trimmed.first().isUpperCase()

        return top3.map { word ->
            when {
                word.isEmpty() -> ""
                isAllCaps -> word.uppercase(Locale.ROOT)
                isCapitalized -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                else -> word
            }
        }
    }

    private fun findSpellCorrections(query: String): List<String> {
        val queryLen = query.length
        if (queryLen < 3) return emptyList()

        val maxDist = when {
            queryLen <= 4 -> 1
            queryLen in 5..7 -> 3
            else -> 3
        }
        val results = mutableListOf<Pair<String, Int>>() // word to score

        fun evaluateCandidates(pool: Iterable<String>, bonusWeight: Int, isIndexRanked: Boolean) {
            var index = 0
            for (word in pool) {
                index++
                val lenDiff = abs(word.length - queryLen)
                if (lenDiff > maxDist) continue

                val sameStart = word.isNotEmpty() && query.isNotEmpty() && word[0] == query[0]
                if (!sameStart && lenDiff >= maxDist) continue

                val startsWith2 = query.length >= 2 && word.length >= 2 && word[0] == query[0] && word[1] == query[1]
                val allowedDist = if (startsWith2 && queryLen >= 5) maxDist else (if (sameStart) maxDist else min(2, maxDist))

                val dist = editDistance(query, word, allowedDist)
                if (dist <= allowedDist) {
                    val suffixBonus = if ((query.endsWith("ly") && word.endsWith("ly")) ||
                        (query.endsWith("ing") && word.endsWith("ing")) ||
                        (query.endsWith("tion") && word.endsWith("tion")) ||
                        (query.endsWith("ed") && word.endsWith("ed"))
                    ) 25 else 0

                    val rankScore = if (isIndexRanked) max(0, 42000 - index) / 300 else 0
                    val score = bonusWeight - (dist * 30) + (if (sameStart) 20 else 0) + (if (startsWith2) 15 else 0) + suffixBonus + rankScore
                    results.add(word to score)
                }
            }
        }

        // 1. Check user learned words first (highest score)
        evaluateCandidates(learnedWordsMap.keys, 90, false)

        // 2. Check Hinglish words
        evaluateCandidates(hinglishWords, 50, false)

        // 3. Check English words starting with same letter (fast bucket of ~1500 words)
        val firstChar = query[0]
        val bucket = wordsByFirstChar[firstChar]
        if (bucket != null) {
            evaluateCandidates(bucket, 40, true)
        } else {
            evaluateCandidates(englishWords.take(5000), 40, true)
        }

        return results.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    private fun editDistance(s1: String, s2: String, maxAllowed: Int): Int {
        val m = s1.length
        val n = s2.length
        if (abs(m - n) > maxAllowed) return maxAllowed + 1

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            var minInRow = curr[0]
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (curr[j] < minInRow) {
                    minInRow = curr[j]
                }
            }
            if (minInRow > maxAllowed) return maxAllowed + 1
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }
}
