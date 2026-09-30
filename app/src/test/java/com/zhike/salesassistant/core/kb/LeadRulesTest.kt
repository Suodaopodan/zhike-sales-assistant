package com.zhike.salesassistant.core.kb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadRulesTest {

    @Test
    fun pendingRequiresActionAndActiveStage() {
        assertTrue(LeadRules.isPending(Contact("1", "张女士", nextAction = "周三回访")))
        assertFalse(LeadRules.isPending(Contact("2", "李先生")))
        assertFalse(LeadRules.isPending(
            Contact("3", "王女士", leadStage = "已成交", nextAction = "发送资料")
        ))
    }

    @Test
    fun filtersUseStableBusinessValues() {
        val contact = Contact(
            id = "1",
            name = "张女士",
            leadStage = "待成交",
            intentLevel = "高",
            nextAction = "今晚确认试听"
        )
        assertTrue(LeadRules.matches(contact, LeadRules.HIGH_INTENT))
        assertTrue(LeadRules.matches(contact, LeadRules.FOLLOW_UP))
        assertFalse(LeadRules.matches(contact, LeadRules.WON))
    }

    @Test
    fun separatesTodayAndOverdueFollowUps() {
        val todayStart = 1_000_000L
        val tomorrowStart = 2_000_000L
        val today = Contact(
            id = "today",
            name = "今日客户",
            nextAction = "电话回访",
            nextFollowUpAt = 1_500_000L
        )
        val overdue = today.copy(id = "overdue", nextFollowUpAt = 500_000L)

        assertTrue(LeadRules.matches(today, LeadRules.TODAY, todayStart, tomorrowStart))
        assertFalse(LeadRules.matches(today, LeadRules.OVERDUE, todayStart, tomorrowStart))
        assertTrue(LeadRules.matches(overdue, LeadRules.OVERDUE, todayStart, tomorrowStart))
    }
}
