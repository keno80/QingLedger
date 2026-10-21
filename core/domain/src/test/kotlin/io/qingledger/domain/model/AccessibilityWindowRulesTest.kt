package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessibilityWindowRulesTest {
    private fun window(id: Int, text: String) = AccessibleWindowReading(id, AccessiblePageSnapshot(text, emptyList(), PageReadStats(1,0,0,0,0,0,false)))
    @Test fun emptyOverlayCanFallBackToOneReceiptWithoutJoiningPageText() {
        val result = AccessibilityWindowRules.choose(listOf(window(1, ""), window(2, "支付成功\n¥1.23")))
        assertEquals(2, result.reading!!.windowId)
        assertEquals(123L, result.decision.proposal!!.amountMinor)
    }
    @Test fun conflictingReceiptsNeverGuessAnAmount() {
        val result = AccessibilityWindowRules.choose(listOf(window(1, "支付成功\n¥1.23"), window(2, "支付成功\n¥9.99")))
        assertNull(result.reading)
        assertEquals("WINDOW_CONFLICT", result.decision.reason)
    }
    @Test fun amountAndSuccessFromDifferentWindowsAreNotCombined() {
        assertNull(AccessibilityWindowRules.choose(listOf(window(1, "支付成功"), window(2, "¥1.23"))).decision.proposal)
    }
}
