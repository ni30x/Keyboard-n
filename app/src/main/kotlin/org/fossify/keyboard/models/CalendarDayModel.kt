package org.fossify.keyboard.models

data class CalendarDayModel(
    val day: Int,
    val month: Int,
    val year: Int,
    val isCurrentMonth: Boolean,
    val isSelected: Boolean,
    val isToday: Boolean,
    val hasSessions: Boolean,
    val sessionCount: Int = 0
)
