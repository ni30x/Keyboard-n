package org.fossify.keyboard.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.keyboard.R
import org.fossify.keyboard.databinding.ItemLearnedWordBinding
import org.fossify.keyboard.models.LearnedWord

class LearnedWordsAdapter(
    private val items: MutableList<LearnedWord>,
    private val onDeleteClicked: (LearnedWord) -> Unit
) : RecyclerView.Adapter<LearnedWordsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemLearnedWordBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LearnedWord) {
            binding.learnedWordText.text = item.word
            binding.learnedWordFrequency.text = binding.root.context.getString(R.string.word_frequency, item.frequency)
            binding.learnedWordDelete.setOnClickListener {
                onDeleteClicked(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLearnedWordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<LearnedWord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeItem(item: LearnedWord) {
        val index = items.indexOfFirst { it.word == item.word }
        if (index != -1) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}
