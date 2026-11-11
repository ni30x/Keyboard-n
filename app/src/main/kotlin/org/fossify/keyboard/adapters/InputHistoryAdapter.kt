package org.fossify.keyboard.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.keyboard.databinding.ItemInputHistorySessionBinding
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.SessionWithDeletions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InputHistoryAdapter(
    private var items: List<SessionWithDeletions> = emptyList()
) : RecyclerView.Adapter<InputHistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault())

    fun updateItems(newItems: List<SessionWithDeletions>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemInputHistorySessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemInputHistorySessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SessionWithDeletions) {
            val session = item.session
            val deletions = item.deletions

            // App Label & Package
            binding.sessionAppLabel.text = session.app_label
            binding.sessionAppPackage.text = session.app_package

            // Status Badge
            val isSent = session.status == InputHistoryRecordingEngine.STATUS_SENT
            binding.sessionStatusBadge.text = session.status

            val badgeStrokeColor = if (isSent) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800")
            val badgeBgColor = if (isSent) Color.parseColor("#224CAF50") else Color.parseColor("#22FF9800")
            val badgeTextColor = if (isSent) Color.parseColor("#388E3C") else Color.parseColor("#F57C00")

            val badgeDrawable = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(badgeBgColor)
                setStroke(2, badgeStrokeColor)
            }
            binding.sessionStatusBadge.background = badgeDrawable
            binding.sessionStatusBadge.setTextColor(badgeTextColor)

            // Timestamp
            binding.sessionTimestamp.text = dateFormat.format(Date(session.start_timestamp))

            // Deletions count summary
            val delCount = deletions.size
            binding.sessionDeletionsCount.text = when (delCount) {
                0 -> "No deletions"
                1 -> "1 deletion recorded"
                else -> "$delCount deletions recorded"
            }

            // Non-destructive deletion ledger rendering:
            // Interleaved text with deleted spans rendered in Red at original positions with NO reflow
            val fullText = session.final_text
            if (fullText.isEmpty()) {
                binding.sessionContentText.text = "(empty session)"
                binding.sessionContentText.alpha = 0.5f
            } else {
                binding.sessionContentText.alpha = 1.0f
                val spannable = SpannableStringBuilder(fullText)

                val redColor = Color.parseColor("#E53935")
                val redBgColor = Color.parseColor("#26E53935")

                for (deletion in deletions) {
                    val start = deletion.position
                    val len = deletion.char_or_word.length
                    val end = (start + len).coerceAtMost(fullText.length)

                    if (start in 0 until fullText.length && end > start) {
                        spannable.setSpan(
                            ForegroundColorSpan(redColor),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            StrikethroughSpan(),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            BackgroundColorSpan(redBgColor),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }

                binding.sessionContentText.text = spannable
            }
        }
    }
}
