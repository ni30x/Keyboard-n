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
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.keyboard.R
import org.fossify.keyboard.databinding.ItemTimeBlockAppGroupBinding
import org.fossify.keyboard.databinding.ItemTimeBlockHeaderBinding
import org.fossify.keyboard.databinding.ItemTimeBlockSessionBinding
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.SessionWithDeletions
import org.fossify.keyboard.models.TimeBlockedItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TimeBlockedHistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_APP_GROUP = 2
        private const val TYPE_SESSION = 3
    }

    private var rawSessions: List<SessionWithDeletions> = emptyList()
    private val displayItems = ArrayList<TimeBlockedItem>()
    private val expandedKeys = HashSet<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setSessions(sessions: List<SessionWithDeletions>) {
        rawSessions = sessions
        rebuildDisplayItems()
    }

    private fun rebuildDisplayItems() {
        displayItems.clear()
        if (rawSessions.isEmpty()) {
            safeNotifyDataSetChanged()
            return
        }

        val cal = Calendar.getInstance()
        // Group by 2-hour block (0..11)
        val sessionsByBlock = rawSessions.groupBy { sessionWithDeletions ->
            cal.timeInMillis = sessionWithDeletions.session.start_timestamp
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            hour / 2
        }

        // Sort blocks chronologically
        val sortedBlocks = sessionsByBlock.keys.sorted()
        for (blockIndex in sortedBlocks) {
            val blockSessions = sessionsByBlock[blockIndex] ?: continue
            val startHour = blockIndex * 2
            val endHour = startHour + 2
            val blockLabel = String.format("%02d:00 - %02d:00", startHour, endHour)

            // Group by app_package inside block
            val appGroups = blockSessions.groupBy { it.session.app_package }

            displayItems.add(
                TimeBlockedItem.TimeBlockHeader(
                    blockIndex = blockIndex,
                    timeRangeLabel = blockLabel,
                    totalSessionsCount = blockSessions.size,
                    totalAppsCount = appGroups.size
                )
            )

            // Sort apps alphabetically or by label
            val sortedPackages = appGroups.keys.sorted()
            for (pkg in sortedPackages) {
                val groupSessions = appGroups[pkg] ?: continue
                val appLabel = groupSessions.firstOrNull()?.session?.app_label ?: pkg
                val expandKey = "$blockIndex:$pkg"
                val isExpanded = expandedKeys.contains(expandKey)

                displayItems.add(
                    TimeBlockedItem.AppGroup(
                        blockIndex = blockIndex,
                        appPackage = pkg,
                        appLabel = appLabel,
                        sessionCount = groupSessions.size,
                        isExpanded = isExpanded
                    )
                )

                if (isExpanded) {
                    for (session in groupSessions) {
                        displayItems.add(
                            TimeBlockedItem.SessionEntry(
                                blockIndex = blockIndex,
                                appPackage = pkg,
                                sessionWithDeletions = session
                            )
                        )
                    }
                }
            }
        }
        safeNotifyDataSetChanged()
    }

    private fun safeNotifyDataSetChanged() {
        try {
            notifyDataSetChanged()
        } catch (_: Exception) {
            // Ignored when unattached in unit tests
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is TimeBlockedItem.TimeBlockHeader -> TYPE_HEADER
            is TimeBlockedItem.AppGroup -> TYPE_APP_GROUP
            is TimeBlockedItem.SessionEntry -> TYPE_SESSION
        }
    }

    override fun getItemCount(): Int = displayItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val binding = ItemTimeBlockHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            TYPE_APP_GROUP -> {
                val binding = ItemTimeBlockAppGroupBinding.inflate(inflater, parent, false)
                AppGroupViewHolder(binding)
            }
            else -> {
                val binding = ItemTimeBlockSessionBinding.inflate(inflater, parent, false)
                SessionViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayItems[position]) {
            is TimeBlockedItem.TimeBlockHeader -> (holder as HeaderViewHolder).bind(item)
            is TimeBlockedItem.AppGroup -> (holder as AppGroupViewHolder).bind(item)
            is TimeBlockedItem.SessionEntry -> (holder as SessionViewHolder).bind(item)
        }
    }

    inner class HeaderViewHolder(
        private val binding: ItemTimeBlockHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TimeBlockedItem.TimeBlockHeader) {
            binding.blockTimeTitle.text = item.timeRangeLabel

            val sessionText = if (item.totalSessionsCount == 1) "1 session" else "${item.totalSessionsCount} sessions"
            val appText = if (item.totalAppsCount == 1) "1 app" else "${item.totalAppsCount} apps"
            binding.blockSessionsBadge.text = "$sessionText · $appText"
        }
    }

    inner class AppGroupViewHolder(
        private val binding: ItemTimeBlockAppGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TimeBlockedItem.AppGroup) {
            binding.appGroupLabel.text = item.appLabel
            binding.appGroupPackage.text = item.appPackage

            val sessionText = if (item.sessionCount == 1) "1 session" else "${item.sessionCount} sessions"
            binding.appGroupCountBadge.text = sessionText

            val context = binding.root.context
            val primaryColor = context.getProperPrimaryColor()

            val badgeDrawable = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#15888888"))
                setStroke(1, Color.parseColor("#30888888"))
            }
            binding.appGroupCountBadge.background = badgeDrawable

            if (item.isExpanded) {
                binding.appGroupChevron.setImageResource(R.drawable.ic_chevron_up_vector)
                binding.appGroupCard.strokeColor = primaryColor
            } else {
                binding.appGroupChevron.setImageResource(R.drawable.ic_chevron_down_vector)
                binding.appGroupCard.setStrokeColor(Color.parseColor("#33888888"))
            }

            binding.appGroupCard.setOnClickListener {
                val expandKey = "${item.blockIndex}:${item.appPackage}"
                if (expandedKeys.contains(expandKey)) {
                    expandedKeys.remove(expandKey)
                } else {
                    expandedKeys.add(expandKey)
                }
                rebuildDisplayItems()
            }
        }
    }

    inner class SessionViewHolder(
        private val binding: ItemTimeBlockSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TimeBlockedItem.SessionEntry) {
            val sessionWithDeletions = item.sessionWithDeletions
            val session = sessionWithDeletions.session
            val deletions = sessionWithDeletions.deletions

            // Exact time
            binding.sessionTime.text = timeFormat.format(Date(session.start_timestamp))

            // Deletions count
            val delCount = deletions.size
            binding.sessionDeletionsCount.text = when (delCount) {
                0 -> "No deletions"
                1 -> "1 deletion"
                else -> "$delCount deletions"
            }

            // Status badge styling supporting all statuses (Phase 3.1 honest statuses and legacy values)
            val (badgeText, badgeStrokeColor, badgeBgColor, badgeTextColor) = when (session.status) {
                InputHistoryRecordingEngine.STATUS_SENT, "0", "COMPLETED" -> {
                    StatusBadgeStyle("SENT", Color.parseColor("#4CAF50"), Color.parseColor("#224CAF50"), Color.parseColor("#388E3C"))
                }
                InputHistoryRecordingEngine.STATUS_LEFT_FIELD -> {
                    StatusBadgeStyle("LEFT FIELD (LIKELY SENT)", Color.parseColor("#0288D1"), Color.parseColor("#220288D1"), Color.parseColor("#0277BD"))
                }
                InputHistoryRecordingEngine.STATUS_DISCARDED, "1" -> {
                    StatusBadgeStyle("DISCARDED", Color.parseColor("#FF9800"), Color.parseColor("#22FF9800"), Color.parseColor("#F57C00"))
                }
                InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN -> {
                    StatusBadgeStyle("CLEARED (UNKNOWN)", Color.parseColor("#78909C"), Color.parseColor("#2278909C"), Color.parseColor("#546E7A"))
                }
                InputHistoryRecordingEngine.STATUS_RECOVERED -> {
                    StatusBadgeStyle("RECOVERED", Color.parseColor("#9C27B0"), Color.parseColor("#229C27B0"), Color.parseColor("#7B1FA2"))
                }
                InputHistoryRecordingEngine.STATUS_DRAFT -> {
                    StatusBadgeStyle("DRAFT", Color.parseColor("#FFB300"), Color.parseColor("#22FFB300"), Color.parseColor("#FF8F00"))
                }
                "ACTIVE", "PENDING_EMPTY" -> {
                    StatusBadgeStyle("OLD ENTRY", Color.parseColor("#9E9E9E"), Color.parseColor("#229E9E9E"), Color.parseColor("#616161"))
                }
                else -> {
                    val rawStatus = session.status ?: "UNKNOWN"
                    StatusBadgeStyle(rawStatus, Color.parseColor("#9E9E9E"), Color.parseColor("#229E9E9E"), Color.parseColor("#616161"))
                }
            }

            binding.sessionStatusBadge.text = badgeText
            val badgeDrawable = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(badgeBgColor)
                setStroke(2, badgeStrokeColor)
            }
            binding.sessionStatusBadge.background = badgeDrawable
            binding.sessionStatusBadge.setTextColor(badgeTextColor)

            // Content box with non-destructive strike-through red deleted spans and NO reflow
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

    private data class StatusBadgeStyle(
        val text: String,
        val strokeColor: Int,
        val bgColor: Int,
        val textColor: Int
    )
}
