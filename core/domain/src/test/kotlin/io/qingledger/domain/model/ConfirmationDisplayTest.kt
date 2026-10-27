package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class ConfirmationDisplayTest {
    @Test fun pendingResultNeverClaimsItWasBooked() {
        assertEquals("识别完成 · 待确认", CaptureNotice.fromState("PENDING").title)
        assertEquals("待确认", CaptureDisplayRules.stateLabel("PENDING"))
    }
    @Test fun pendingFilterDoesNotShowSettledOrDiscardedItems() {
        assertTrue(CaptureDisplayRules.matches("PENDING", 3))
        listOf("AUTO_POSTED", "CONFIRMED", "MERGED", "IGNORED", "SKIPPED").forEach { assertFalse(CaptureDisplayRules.matches(it, 3)) }
    }
    @Test fun connectedAuthorizedShizukuIsAWorkingMethod() {
        assertEquals("运行正常", CaptureDisplayRules.status(CaptureMethod.SHIZUKU, true, true, true, 1))
    }
}
