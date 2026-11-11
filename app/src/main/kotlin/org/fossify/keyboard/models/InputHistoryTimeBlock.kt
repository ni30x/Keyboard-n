package org.fossify.keyboard.models

import java.util.Calendar
import java.util.Locale

data class AppSessionGroup(
    val appPackage: String,
    val appLabel: String,
    val sessions: List<SessionWithDeletions>,
    var isExpanded: Boolean = false
)

data class InputHistoryTimeBlock(
    val blockIndex: Int,
    val label: String,
    val appGroups: List<AppSessionGroup>
) {
    val totalSessions: Int get() = appGroups.sumOf { it.sessions.size }
    val totalDeletions: Int get() = appGroups.sumOf { group -> group.sessions.sumOf { it.deletions.size } }
}

object TimeBlockGrouper {
    fun groupSessions(sessions: List<SessionWithDeletions>): List<InputHistoryTimeBlock> {
        val cal = Calendar.getInstance()
        val blockMap = sessions.groupBy { sessionWithDeletions ->
            cal.timeInMillis = sessionWithDeletions.session.start_timestamp
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            (hour / 2).coerceIn(0, 11)
        }

        return blockMap.keys.sorted().map { blockIdx ->
            val blockSessions = blockMap[blockIdx] ?: emptyList()
            val startHour = blockIdx * 2
            val endHour = startHour + 2
            val label = String.format(Locale.getDefault(), "%02d:00 – %02d:00", startHour, endHour)

            val appMap = blockSessions.groupBy { it.session.app_package }
            val appGroups = appMap.map { (pkg, appSessions) ->
                val labelName = appSessions.firstOrNull()?.session?.app_label?.takeIf { it.isNotBlank() } ?: pkg
                AppSessionGroup(
                    appPackage = pkg,
                    appLabel = labelName,
                    sessions = appSessions,
                    isExpanded = false
                )
            }.sortedByDescending { it.sessions.size }

            InputHistoryTimeBlock(
                blockIndex = blockIdx,
                label = label,
                appGroups = appGroups
            )
        }
    }
}
