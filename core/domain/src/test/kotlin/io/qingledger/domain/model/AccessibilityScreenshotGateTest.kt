package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessibilityScreenshotGateTest {
    @Test fun onlyEmptyCurrentAuthorizedPagesCanRequestScreenshot() {
        val gate = AccessibilityScreenshotGate()
        assertFalse(gate.request("wechat", 0, 1, false, true, 100))
        assertFalse(gate.request("wechat", 0, 1, true, false, 100))
        assertTrue(gate.request("wechat", 0, 1, true, true, 100))
    }

    @Test fun duplicateEventsAreThrottledButNewWindowsAndDisplaysAreIndependent() {
        val gate = AccessibilityScreenshotGate()
        assertTrue(gate.request("wechat", 0, 1, true, true, 100))
        assertFalse(gate.request("wechat", 0, 1, true, true, 101))
        assertTrue(gate.request("wechat", 0, 2, true, true, 101))
        assertTrue(gate.request("wechat", 1, 1, true, true, 101))
        assertFalse(gate.request("wechat", 0, 1, true, true, 30099))
        assertTrue(gate.request("wechat", 0, 1, true, true, 30100))
    }

    @Test fun rejectedRequestsDoNotConsumeCooldownAndInvalidWindowsNeverCapture() {
        val gate = AccessibilityScreenshotGate()
        assertFalse(gate.request("wechat", 0, -1, true, true, 100))
        assertFalse(gate.request("wechat", -1, 1, true, true, 100))
        assertFalse(gate.request("wechat", 0, 1, true, false, 100))
        assertTrue(gate.request("wechat", 0, 1, true, true, 100))
    }
}
