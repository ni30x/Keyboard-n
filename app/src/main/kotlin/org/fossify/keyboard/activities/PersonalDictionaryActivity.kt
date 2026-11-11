package org.fossify.keyboard.activities

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.keyboard.R
import org.fossify.keyboard.adapters.LearnedWordsAdapter
import org.fossify.keyboard.databases.LearnedWordsDatabase
import org.fossify.keyboard.databinding.ActivityPersonalDictionaryBinding
import org.fossify.keyboard.helpers.SuggestionEngine
import org.fossify.keyboard.models.LearnedWord

class PersonalDictionaryActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityPersonalDictionaryBinding::inflate)
    private lateinit var adapter: LearnedWordsAdapter
    private val wordItems = ArrayList<LearnedWord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupTopAppBar(binding.personalDictionaryAppbar, NavigationIcon.Arrow)
        binding.personalDictionaryToolbar.inflateMenu(R.menu.menu_personal_dictionary)
        binding.personalDictionaryToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.clear_all_words -> {
                    confirmClearAll()
                    true
                }
                else -> false
            }
        }

        adapter = LearnedWordsAdapter(wordItems) { wordItem ->
            confirmDeleteWord(wordItem)
        }
        binding.learnedWordsList.adapter = adapter

        loadWords()
    }

    private fun loadWords() {
        lifecycleScope.launch(Dispatchers.IO) {
            val words = try {
                LearnedWordsDatabase.getInstance(this@PersonalDictionaryActivity).learnedWordsDao().getAll()
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                adapter.updateItems(words)
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        val isEmpty = adapter.itemCount == 0
        binding.learnedWordsPlaceholder.beVisibleIf(isEmpty)
        binding.learnedWordsList.beVisibleIf(!isEmpty)
    }

    private fun confirmDeleteWord(item: LearnedWord) {
        ConfirmationDialog(
            activity = this,
            message = getString(R.string.delete_word_confirm, item.word),
            positive = R.string.delete,
            negative = R.string.cancel
        ) {
            SuggestionEngine.deleteLearnedWord(this, item.word)
            adapter.removeItem(item)
            updateEmptyState()
        }
    }

    private fun confirmClearAll() {
        if (adapter.itemCount == 0) return
        ConfirmationDialog(
            activity = this,
            message = getString(R.string.clear_learned_words_confirm),
            positive = R.string.clear_all_learned_words,
            negative = R.string.cancel
        ) {
            SuggestionEngine.clearAllLearnedWords(this)
            adapter.updateItems(emptyList())
            updateEmptyState()
            toast(R.string.learned_words_cleared)
        }
    }
}
