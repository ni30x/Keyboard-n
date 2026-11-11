package org.fossify.keyboard.models

sealed class TimeBlockedItem {
    data class TimeBlockHeader(
        val blockIndex: Int,
        val timeRangeLabel: String,
        val totalSessionsCount: Int,
        val totalAppsCount: Int
    ) : TimeBlockedItem()

    data class AppGroup(
        val blockIndex: Int,
        val appPackage: String,
        val appLabel: String,
        val sessionCount: Int,
        var isExpanded: Boolean = false
    ) : TimeBlockedItem()

    data class SessionEntry(
        val blockIndex: Int,
        val appPackage: String,
        val sessionWithDeletions: SessionWithDeletions
    ) : TimeBlockedItem()
}
