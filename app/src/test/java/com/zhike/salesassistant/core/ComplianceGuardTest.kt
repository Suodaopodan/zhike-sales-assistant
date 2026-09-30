package com.zhike.salesassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplianceGuardTest {

    @Test
    fun blocksEducationOutcomeGuarantees() {
        assertFalse(ComplianceGuard.isAllowed("报名后保证提分，考试包过"))
        assertFalse(ComplianceGuard.isAllowed("使用内部原题，百分之百通过"))
    }

    @Test
    fun allowsQualifiedFactualLanguage() {
        assertTrue(
            ComplianceGuard.isAllowed(
                "课程效果会因基础和投入不同而有差异，我先了解一下孩子目前的情况。"
            )
        )
    }

    @Test
    fun replacesUnsafeCandidatesAndKeepsThree() {
        val result = ComplianceGuard.sanitizeCandidates(
            listOf(
                "我们保证提分，报名就能上岸",
                "您方便说下孩子目前几年级吗？",
                "全国第一名师团队，最后一个名额"
            )
        )

        assertEquals(3, result.size)
        assertTrue(result.all { ComplianceGuard.isAllowed(it) })
        assertTrue(result.contains("您方便说下孩子目前几年级吗？"))
    }
}
