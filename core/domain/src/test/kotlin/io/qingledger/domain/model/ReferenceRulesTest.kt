package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class ReferenceRulesTest {
    @Test fun namesAreTrimmedWithoutChangingTheirContent() {
        assertEquals("旅行基金", ReferenceRules.normalizeName("  旅行基金  "))
        assertEquals("Daily wallet", ReferenceRules.normalizeName("Daily wallet"))
    }
    @Test fun blankOverlongAndControlCharacterNamesAreRejected() {
        listOf("", "   ", "a".repeat(33), "账户\n名称", "\u0000").forEach { assertNull(ReferenceRules.normalizeName(it)) }
    }
}
