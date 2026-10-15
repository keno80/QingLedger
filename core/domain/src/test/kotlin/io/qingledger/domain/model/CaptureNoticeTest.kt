package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class CaptureNoticeTest {
    @Test fun successfulPersistenceIsRequiredForBookedMessage() {
        assertEquals("识别完成 · 已入账", CaptureNotice.fromState("AUTO_POSTED").title)
        assertEquals("识别完成 · 已关联", CaptureNotice.fromState("MERGED").title)
        assertEquals("识别完成 · 已跳过", CaptureNotice.fromState("SKIPPED").title)
        assertEquals("识别未完成", CaptureNotice.fromState(null).title)
        assertFalse(CaptureNotice.fromState("PENDING").title.contains("已入账"))
    }
    @Test fun repeatedDigestDoesNotStartAnotherNoticeAndMemoryIsBounded() {
        val gate = CaptureNoticeGate(2)
        assertTrue(gate.accept("first"))
        assertFalse(gate.accept("first"))
        assertTrue(gate.accept("second"))
        assertTrue(gate.accept("third"))
        assertTrue(gate.accept("first"))
    }
    @Test fun notificationTextDoesNotExposeFinancialFields() {
        listOf("AUTO_POSTED", "MERGED", "SKIPPED", "PENDING").forEach {
            val text = CaptureNotice.fromState(it)
            assertFalse((text.title + text.description).contains("¥"))
            assertFalse((text.title + text.description).contains("银行卡"))
        }
    }
}
