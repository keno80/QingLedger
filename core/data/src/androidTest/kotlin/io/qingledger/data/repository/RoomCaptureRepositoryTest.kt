package io.qingledger.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.domain.model.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RoomCaptureRepositoryTest {
    @Test fun reusedNotificationSessionDoesNotMergeRefundIntoExpense() = runBlocking {
        enable()
        capture.capture(event("notice:shared", "expense"), proposal)
        capture.capture(event("notice:shared", "refund"), proposal.copy(kind = TransactionKind.REFUND))
        assertEquals(2, capture.candidates.first().count { it.state == "AUTO_POSTED" })
        assertEquals(0L, ledger.summary(period).first().expense)
    }
    @Test fun successfulRefundAutoPostsAndReducesNetExpenseWithoutGuessingOriginal() = runBlocking {
        enable()
        capture.capture(event("notice:expense"), proposal)
        capture.capture(event("notice:refund"), proposal.copy(kind = TransactionKind.REFUND))
        val rows = capture.candidates.first()
        assertEquals(2, rows.count { it.state == "AUTO_POSTED" })
        val refund = ledger.get(rows.single { it.kind == TransactionKind.REFUND }.transactionId!!)!!
        assertNull(refund.originalExpenseId)
        assertNull(refund.accountId)
        assertEquals(0L, ledger.summary(period).first().income)
        assertEquals(0L, ledger.summary(period).first().expense)
    }
    @Test fun refundUpdateMergesButSeparateSameAmountRefundsRemainSeparate() = runBlocking {
        enable()
        val refund = proposal.copy(kind = TransactionKind.REFUND)
        capture.capture(event("notice:r1"), refund)
        capture.capture(event("notice:r1", "expanded"), refund)
        capture.capture(event("notice:r2"), refund)
        assertEquals(2, capture.candidates.first().count { it.state == "AUTO_POSTED" })
        assertEquals(1, capture.candidates.first().count { it.state == "MERGED" })
        assertEquals(-2468L, ledger.summary(period).first().expense)
    }
    @Test fun recognizedWalletUsesExistingAccount() = runBlocking {
        enable(); capture.capture(event(), proposal.copy(accountName = "微信零钱"))
        val id = capture.candidates.first().single().transactionId!!
        assertEquals(ledger.accounts.first().single { it.name == "微信零钱" }.id, ledger.get(id)!!.accountId)
        assertEquals(4, ledger.accounts.first().size)
    }
    @Test fun recognizedCardsAreCreatedOnceAndDifferentTailsRemainSeparate() = runBlocking {
        enable()
        capture.capture(event("a"), proposal.copy(accountName = "测试银行储蓄卡（1234）"))
        val firstId = capture.candidates.first().single().transactionId!!
        capture.capture(event("b"), proposal.copy(accountName = "测试银行储蓄卡（1234）"))
        capture.capture(event("c"), proposal.copy(accountName = "测试银行储蓄卡（5678）"))
        val rows = capture.candidates.first().map { ledger.get(it.transactionId!!)!! }
        val accountOne = ledger.accounts.first().single { it.name == "测试银行储蓄卡（1234）" }
        assertEquals(accountOne.id, ledger.get(firstId)!!.accountId)
        assertEquals(2, rows.count { it.accountId == accountOne.id })
        assertEquals(6, ledger.accounts.first().size)
        assertEquals(2, rows.map { it.accountId }.distinct().size)
    }
    @Test fun archivedAndUnknownAccountsAreNotRecreatedOrGuessed() = runBlocking {
        val id = ledger.saveAccount("测试银行储蓄卡（1234）")
        ledger.archiveAccount(id)
        enable()
        capture.capture(event("archived"), proposal.copy(accountName = "测试银行储蓄卡（1234）"))
        capture.capture(event("unknown"), proposal.copy(accountName = "银行卡"))
        assertTrue(capture.candidates.first().all { ledger.get(it.transactionId!!)!!.accountId == null })
        assertEquals(5, ledger.accounts.first().size)
        assertTrue(ledger.accounts.first().single { it.id == id }.archived)
    }
    private lateinit var database: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var capture: RoomCaptureRepository
    private val source = "com.eg.android.AlipayGphone"
    private val now = Instant.parse("2026-10-13T06:00:00Z").toEpochMilli()
    private val period = LedgerRules.period(YearMonth.of(2026, 10), ZoneId.of("Asia/Shanghai"))
    private val proposal = NotificationProposal(1234, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE")
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LedgerDatabase::class.java).build()
        ledger = RoomLedgerRepository(database)
        ledger.initialize()
        capture = RoomCaptureRepository(database, ledger)
    }
    @After fun teardown() { database.close() }
    @Test fun overviewCountIncludesHiddenRecordsAndIgnoresListLimitAndManualConfirmation() = runBlocking {
        enable()
        repeat(205) { i ->
            val e = event("overview-$i").copy(receivedAt = now + i * 180000L)
            capture.capture(e, proposal)
        }
        val before = capture.overview(PeriodRange(now, now + 206 * 180000L)).first()
        assertEquals(205, before.todayCount)
        assertEquals(now + 204 * 180000L, before.latest!!.receivedAt)
        assertEquals(200, capture.candidates.first().size)
        database.captureDao().insert(io.qingledger.data.database.NotificationCandidateEntity("legacy-overview", source, "user-0", "manual-overview", "legacy-digest", 100, now + 205 * 180000L, 1500, "EXPENSE", "UNVERIFIED_TEMPLATE"))
        val manual = capture.candidates.first().first { it.state == "PENDING" }
        capture.confirm(manual.id, TransactionDraft(TransactionKind.EXPENSE, 1500, now + 205 * 180000L))
        capture.clearResolved()
        assertTrue(capture.candidates.first().isEmpty())
        assertEquals(205, capture.overview(PeriodRange(now, now + 206 * 180000L)).first().todayCount)
        assertEquals(0, capture.overview(PeriodRange(now - 1, now)).first().todayCount)
    }
    private suspend fun enable() { capture.setSource(source, true); capture.setEnabled(true) }
    private suspend fun event(session: String = "session", digest: String = "digest") = CapturedNotification(source, "user-0", session, digest, now, 100, capture.settings.first().generation)
    @Test fun collectionStartsDisabledAndUnknownSourcesCannotBeEnabled() = runBlocking {
        assertFalse(capture.settings.first().enabled)
        assertFalse(capture.capture(event(), proposal))
        try { capture.setSource("arbitrary.app", true); fail("unknown source accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun pauseAndSourceChangesInvalidateAlreadyQueuedEvents() = runBlocking {
        enable()
        val queued = event()
        capture.setEnabled(false)
        assertFalse(capture.capture(queued, proposal))
        capture.setEnabled(true)
        assertFalse(capture.capture(queued, proposal))
        val fresh = event()
        capture.setSource(source, false)
        assertFalse(capture.capture(fresh, proposal))
        assertEquals(0, capture.pendingCount.first())
    }
    @Test fun replayConfirmationAndDeletionCannotDuplicateLedgerEntries() = runBlocking {
        enable()
        val notification = event()
        repeat(10) { capture.capture(notification, proposal) }
        assertEquals(0, capture.pendingCount.first())
        assertEquals(1234L, ledger.summary(period).first().expense)
        val candidate = capture.candidates.first().single()
        assertEquals("AUTO_POSTED", candidate.state)
        val draft = TransactionDraft(TransactionKind.EXPENSE, 1234, now)
        val id = capture.confirm(candidate.id, draft)
        assertEquals(id, capture.confirm(candidate.id, draft.copy(amountMinor = 999)))
        assertEquals(1234L, ledger.summary(period).first().expense)
        assertEquals(source, ledger.get(id)!!.sourcePackage)
        capture.clearResolved()
        assertTrue(capture.candidates.first().isEmpty())
        ledger.delete(id, 0)
        assertFalse(capture.capture(notification, proposal))
        assertEquals(0L, ledger.summary(period).first().expense)
    }
    @Test fun incompleteNotificationIsSkippedAndCannotBeConfirmed() = runBlocking {
        enable(); capture.capture(event(), proposal.copy(amountMinor = null, reason = "MISSING_AMOUNT"))
        val candidate = capture.candidates.first().single()
        try { capture.confirm(candidate.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now, accountId = "missing")); fail("invalid draft accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("SKIPPED", capture.candidates.first().single().state)
        assertEquals(0L, ledger.summary(period).first().expense)
    }
    @Test fun weakSameAmountMatchDoesNotMergeSeparateTransactions() = runBlocking {
        enable(); capture.capture(event("first"), proposal)
        val id = capture.confirm(capture.candidates.first().single().id, TransactionDraft(TransactionKind.EXPENSE, 1234, now, merchant = "Preserve"))
        capture.capture(event("second"), proposal)
        val duplicate = capture.candidates.first().first { it.state == "AUTO_POSTED" && it.transactionId != id }
        assertEquals("POSSIBLE_DUPLICATE", duplicate.reason)
        assertEquals(id, duplicate.relatedTransactionId)
        assertEquals(2468L, ledger.summary(period).first().expense)
        assertEquals(source, ledger.get(id)!!.sourcePackage)
    }
    @Test fun sameAmountSeparatePaymentsAutomaticallyBook() = runBlocking {
        enable(); capture.capture(event("a"), proposal); capture.capture(event("b"), proposal)
        assertEquals(0, capture.pendingCount.first())
        val candidates = capture.candidates.first()
        candidates.forEach { capture.confirm(it.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now)) }
        assertEquals(2468L, ledger.summary(period).first().expense)
    }
    @Test fun refundUpdateCannotBypassRefundBlockAsDuplicate() = runBlocking {
        enable(); capture.capture(event(), proposal)
        capture.confirm(capture.candidates.first().single().id, TransactionDraft(TransactionKind.EXPENSE, 1234, now))
        capture.capture(event(digest = "refund"), NotificationProposal(1234, null, "REFUND_REVIEW"))
        val refund = capture.candidates.first().first { it.state == "SKIPPED" }
        assertEquals("REFUND_REVIEW", refund.reason)
        try { capture.confirm(refund.id, TransactionDraft(TransactionKind.INCOME, 1234, now)); fail("refund treated as income") } catch (_: IllegalArgumentException) { }
        assertEquals(0L, ledger.summary(period).first().income)
    }
    @Test fun updateDoesNotOverwriteUserCorrection() = runBlocking {
        enable(); capture.capture(event(), proposal)
        val id = capture.candidates.first().single().transactionId!!
        ledger.save(TransactionDraft(TransactionKind.EXPENSE, 700, now, merchant = "用户修正"), id, 0)
        capture.capture(event(digest = "update"), proposal.copy(amountMinor = 999))
        assertEquals(700L, ledger.summary(period).first().expense)
        assertEquals(source, ledger.get(id)!!.sourcePackage)
        assertEquals(0, capture.pendingCount.first())
        assertEquals("MERGED", capture.candidates.first().first { it.relatedTransactionId == id }.state)
    }
}
