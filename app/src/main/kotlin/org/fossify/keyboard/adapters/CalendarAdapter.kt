package org.fossify.keyboard.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.keyboard.R
import org.fossify.keyboard.databinding.ItemCalendarDayBinding
import org.fossify.keyboard.models.CalendarDayModel

class CalendarAdapter(
    private var days: List<CalendarDayModel> = emptyList(),
    private val onDayClicked: (CalendarDayModel) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.ViewHolder>() {

    fun setDays(newDays: List<CalendarDayModel>) {
        days = newDays
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCalendarDayBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(days[position])
    }

    override fun getItemCount(): Int = days.size

    inner class ViewHolder(
        private val binding: ItemCalendarDayBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CalendarDayModel) {
            val context = binding.root.context
            val primaryColor = context.getProperPrimaryColor()
            val textColor = context.getProperTextColor()

            if (!item.isCurrentMonth || item.day <= 0) {
                binding.dayText.text = if (item.day > 0) item.day.toString() else ""
                binding.dayText.setTextColor(Color.parseColor("#44888888"))
                binding.dayDot.beVisibleIf(false)
                binding.dayContainer.background = null
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
                return
            }

            binding.dayText.text = item.day.toString()
            binding.root.isClickable = true
            binding.root.setOnClickListener {
                onDayClicked(item)
            }

            // Dot indicator for recorded sessions
            binding.dayDot.beVisibleIf(item.hasSessions)

            if (item.isSelected) {
                // Circular filled pill for selected day
                val selectedBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primaryColor)
                }
                binding.dayContainer.background = selectedBg
                binding.dayText.setTextColor(Color.WHITE)
                binding.dayText.setTypeface(null, android.graphics.Typeface.BOLD)

                val whiteDot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                binding.dayDot.background = whiteDot
            } else if (item.isToday) {
                // Outlined circular pill for today
                val todayBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(3, primaryColor)
                }
                binding.dayContainer.background = todayBg
                binding.dayText.setTextColor(primaryColor)
                binding.dayText.setTypeface(null, android.graphics.Typeface.BOLD)

                val primaryDot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primaryColor)
                }
                binding.dayDot.background = primaryDot
            } else {
                // Normal unselected day
                binding.dayContainer.background = null
                binding.dayText.setTextColor(textColor)
                binding.dayText.setTypeface(null, android.graphics.Typeface.NORMAL)

                val primaryDot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primaryColor)
                }
                binding.dayDot.background = primaryDot
            }
        }
    }
}
