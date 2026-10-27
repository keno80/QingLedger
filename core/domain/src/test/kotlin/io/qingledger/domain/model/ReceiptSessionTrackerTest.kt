package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class ReceiptSessionTrackerTest {
    private var sequence = 0
    private fun tracker() = ReceiptSessionTracker("test-profile") { "session-${++sequence}" }
    private val paid = NotificationProposal(450, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE")
    @Test fun returnToSuccessAndServiceRestartReuseTheBookedSessionWithoutAccountIdentity() {
        val tracker = tracker()
        tracker.confirmation("wechat", 450, "测试商户", 1, 0)
        val first = tracker.success("wechat", paid, "测试商户", 1, 1000)
        assertFalse(first.revisit)
        assertEquals(first.session, tracker.success("wechat", paid.copy(accountName = null), "测试商户", 1, 19000).session)
        val restored = tracker(); restored.restore(tracker.state)
        val repeated = restored.success("wechat", paid, "测试商户", 2, 90000)
        assertTrue(repeated.revisit); assertEquals(first.session, repeated.session)
    }
    @Test fun aFreshConfirmationAllowsAnotherGenuineIdenticalPayment() {
        val tracker = tracker()
        tracker.confirmation("wechat", 450, "测试商户", 1, 0)
        tracker.confirmation("wechat", 450, "测试商户", 1, 500)
        val first = tracker.success("wechat", paid, "测试商户", 1, 1000)
        tracker.confirmation("wechat", 450, "测试商户", 1, 2000)
        val second = tracker.success("wechat", paid, "测试商户", 1, 3000)
        assertFalse(second.revisit); assertNotEquals(first.session, second.session)
    }
    @Test fun missingOrEnrichedMerchantDoesNotCreateASecondBillWithoutFreshConfirmation() {
        val tracker = tracker()
        val first = tracker.success("wechat", paid, null, 1, 0)
        assertEquals(first.session, tracker.success("wechat", paid, "测试商户", 1, 500).session)
        assertEquals(first.session, tracker.success("wechat", paid, null, 1, 1000).session)
        assertNotEquals(first.session, tracker.success("wechat", paid, "另一商户", 1, 2000).session)
    }
    @Test fun existingLedgerReceiptsAreSeededButFreshConfirmationDoesNotMergeIntoThem() {
        val tracker = tracker()
        tracker.seed("wechat", TransactionKind.EXPENSE, 450, "测试商户", "persisted-session")
        assertEquals("persisted-session", tracker.success("wechat", paid, "测试商户", 1, 0).session)
        tracker.confirmation("wechat", 450, "测试商户", 1, 1000)
        assertNotEquals("persisted-session", tracker.success("wechat", paid, "测试商户", 1, 2000).session)
        assertFalse(tracker.state.toString().contains("测试商户"))
    }
    @Test fun sourceKindAndAmountAreSeparatedAndHistoryIsBounded() {
        val tracker = tracker()
        val first = tracker.success("wechat", paid, "商户", 1, 0)
        assertNotEquals(first.session, tracker.success("alipay", paid, "商户", 1, 1).session)
        assertNotEquals(first.session, tracker.success("wechat", paid.copy(kind = TransactionKind.INCOME), "商户", 1, 2).session)
        assertNotEquals(first.session, tracker.success("wechat", paid.copy(amountMinor = 451), "商户", 1, 3).session)
        repeat(100) { tracker.success("wechat", paid.copy(amountMinor = 500L + it), "商户", 1, 4L + it) }
        assertTrue(tracker.state.recent.size <= 64)
    }
    @Test fun restoringABackupDoesNotKeepReferencesToSessionsAbsentFromTheLedger() {
        val tracker = tracker()
        tracker.seed("wechat", TransactionKind.EXPENSE, 450, "测试商户", "stale")
        tracker.reconcile(setOf("restored"))
        tracker.seed("wechat", TransactionKind.EXPENSE, 450, "测试商户", "restored")
        assertEquals("restored", tracker.success("wechat", paid, "测试商户", 1, 0).session)
    }
    @Test fun sameReceiptVisualSurvivesOcrSpellingVariationButNeverOverridesFreshConfirmation() {
        val tracker = tracker()
        val first = tracker.success("wechat", paid, "测试商户", 1, 0, "stable-image")
        assertEquals(first.session, tracker.success("wechat", paid, "測测试商户", 1, 1000, "stable-image").session)
        tracker.confirmation("wechat", 450, "测试商户", 1, 2000)
        assertNotEquals(first.session, tracker.success("wechat", paid, "测试商户", 1, 3000, "stable-image").session)
    }
}
