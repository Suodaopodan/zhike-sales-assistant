package com.zhike.salesassistant.core.kb

object LeadRules {
    const val ALL = "全部"
    const val HIGH_INTENT = "高意向"
    const val FOLLOW_UP = "待跟进"
    const val TODAY = "今日待办"
    const val OVERDUE = "已逾期"
    const val WON = "已成交"

    val filters = listOf(ALL, TODAY, OVERDUE, HIGH_INTENT, FOLLOW_UP, WON)

    fun isPending(contact: Contact): Boolean =
        contact.nextAction.isNotBlank() && contact.leadStage !in setOf("已成交", "暂缓")

    fun isDueToday(contact: Contact, todayStart: Long, tomorrowStart: Long): Boolean =
        isPending(contact) && contact.nextFollowUpAt in todayStart until tomorrowStart

    fun isOverdue(contact: Contact, todayStart: Long): Boolean =
        isPending(contact) && contact.nextFollowUpAt in 1 until todayStart

    fun matches(
        contact: Contact,
        filter: String,
        todayStart: Long = 0L,
        tomorrowStart: Long = Long.MAX_VALUE
    ): Boolean = when (filter) {
        HIGH_INTENT -> contact.intentLevel == "高"
        FOLLOW_UP -> isPending(contact)
        TODAY -> isDueToday(contact, todayStart, tomorrowStart)
        OVERDUE -> isOverdue(contact, todayStart)
        WON -> contact.leadStage == "已成交"
        else -> true
    }

    fun priority(contact: Contact, todayStart: Long = 0L): Int =
        (if (todayStart > 0 && isOverdue(contact, todayStart)) 200 else 0) +
        (if (contact.intentLevel == "高") 100 else 0) +
            (if (isPending(contact)) 20 else 0) +
            when (contact.leadStage) {
                "待成交" -> 10
                "已试听" -> 8
                "已沟通" -> 5
                else -> 0
            }
}
