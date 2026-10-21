package io.qingledger.domain

import io.qingledger.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CaptureDisplayRulesTest {
    @Test fun methodStatusNeverClaimsUnimplementedOrDisconnectedCollection() {
        assertEquals("尚未接入", CaptureDisplayRules.status(CaptureMethod.ROOT, true, true, true, 1))
        assertEquals("未授权", CaptureDisplayRules.status(CaptureMethod.ACCESSIBILITY, true, false, false, 1))
        assertEquals("已暂停", CaptureDisplayRules.status(CaptureMethod.ACCESSIBILITY, false, true, true, 1))
        assertEquals("未连接", CaptureDisplayRules.status(CaptureMethod.ACCESSIBILITY, true, true, false, 1))
        assertEquals("未选择来源", CaptureDisplayRules.status(CaptureMethod.ACCESSIBILITY, true, true, true, 0))
        assertEquals("运行正常", CaptureDisplayRules.status(CaptureMethod.ACCESSIBILITY, true, true, true, 1))
    }
    @Test fun postedAndSkippedFiltersKeepLegacyStatesExplicit() {
        assertTrue(CaptureDisplayRules.matches("AUTO_POSTED", 1))
        assertTrue(CaptureDisplayRules.matches("CONFIRMED", 1))
        assertFalse(CaptureDisplayRules.matches("MERGED", 1))
        assertTrue(CaptureDisplayRules.matches("SKIPPED", 2))
        assertTrue(CaptureDisplayRules.matches("IGNORED", 2))
        assertFalse(CaptureDisplayRules.matches("PENDING", 2))
        assertTrue(CaptureDisplayRules.matches("MERGED", 0))
    }
}
