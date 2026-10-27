package io.qingledger.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.domain.model.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*

class RoomCaptureRepositoryTest {
    @Test fun pendingAndIgnoredReceiptsSurviveSixtyFivePostedAccountHistoryRows() = runBlocking {
        enable()
        repeat(65) { index ->
            capture.capture(event("a11y:posted-$index").copy(receivedAt = now - (index + 1) * 180000L),
                proposal.copy(amountMinor = 1000L + index, merchantName = "历史商户$index", accountName = "微信零钱"))
        }
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        capture.capture(event("a11y:ignored-latest"), proposal.copy(amountMinor = 9000, merchantName = "忽略商户"))
        capture.ignore(capture.candidates.first().single { it.state == "PENDING" }.id)
        capture.clearResolved()
        capture.capture(event("a11y:pending-latest").copy(receivedAt = now + 1), proposal.copy(amountMinor = 10000, merchantName = "待确认商户"))
        val history = capture.accessibilityReceipts("user-0")
        assertEquals(64, history.size)
        assertTrue(history.any { it.session == "a11y:ignored-latest" })
        assertTrue(history.any { it.session == "a11y:pending-latest" })
        assertEquals(setOf("a11y:ignored-latest", "a11y:pending-latest"), history.take(2).map { it.session }.toSet())
        val tracker = ReceiptSessionTracker("user-0") { "a11y:unexpected-new-session" }
        history.forEach { tracker.seed(it.source, it.kind, it.amountMinor, it.merchant, it.session) }
        assertEquals("a11y:ignored-latest", tracker.success(source, proposal.copy(amountMinor = 9000), "忽略商户", capture.settings.first().generation, now).session)
    }
    @Test fun editedConfirmationMerchantStillSeedsOriginalRecognitionForReplay() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        val notification = event("a11y:edited-merchant")
        capture.capture(notification, proposal.copy(merchantName = "识别商户"))
        val pending = capture.candidates.first().single()
        capture.confirm(pending.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now, merchant = "用户修正商户"))
        val history = capture.accessibilityReceipts("user-0")
        assertEquals("识别商户", history.single().merchant)
        val tracker = ReceiptSessionTracker("user-0") { "a11y:unexpected-new-session" }
        history.forEach { tracker.seed(it.source, it.kind, it.amountMinor, it.merchant, it.session) }
        assertEquals(notification.sessionKey, tracker.success(source, proposal, "识别商户", notification.generation, now).session)
    }
    @Test fun confirmationCreatesRecognizedAccountOnlyWhenConfirmedAndIsIdempotent() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        val accountName = "测试银行储蓄卡（5678）"
        capture.capture(event("confirm-account"), proposal.copy(accountName = accountName))
        val pending = capture.candidates.first().single()
        assertEquals(4, ledger.accounts.first().size)
        val id = capture.confirmWithAccount(pending.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now), accountName)
        assertEquals(5, ledger.accounts.first().size)
        val account = ledger.accounts.first().single { it.name == accountName }
        assertEquals(account.id, ledger.get(id)!!.accountId)
        assertEquals(id, capture.confirmWithAccount(pending.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now), "另一银行储蓄卡（9999）"))
        assertEquals(5, ledger.accounts.first().size)
        assertEquals(1, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun invalidConfirmationRollsBackNewRecognizedAccountAndLeavesCandidatePending() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        capture.capture(event("invalid-account"), proposal)
        val pending = capture.candidates.first().single()
        val before = ledger.accounts.first()
        try {
            capture.confirmWithAccount(pending.id, TransactionDraft(TransactionKind.EXPENSE, 0, now), "测试银行储蓄卡（5678）")
            fail("invalid confirmation accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, ledger.accounts.first())
        assertEquals("PENDING", capture.candidate(pending.id).first()!!.state)
        assertEquals(0, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun explicitUnspecifiedAccountAndExistingSelectionDoNotGuessOrCreateRecognizedAccounts() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        val name = "测试银行储蓄卡（5678）"
        capture.capture(event("unspecified"), proposal.copy(accountName = name))
        val unspecified = capture.candidates.first().single()
        val noAccount = capture.confirmWithAccount(unspecified.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now), null)
        assertNull(ledger.get(noAccount)!!.accountId)
        capture.capture(event("explicit-selection"), proposal.copy(accountName = name))
        val selected = capture.candidates.first().single { it.state == "PENDING" }
        val chosen = capture.confirmWithAccount(selected.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now, accountId = "account-0"), name)
        assertEquals("account-0", ledger.get(chosen)!!.accountId)
        assertEquals(4, ledger.accounts.first().size)
    }
    @Test fun confirmationMatchesExistingBankIdentityAndDoesNotGuessAmbiguousOrArchivedAccounts() = runBlocking {
        val debit = ledger.saveAccount("测试银行储蓄卡（1234）")
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        suspend fun confirmed(session: String): String {
            capture.capture(event(session), proposal)
            val pending = capture.candidates.first().single { it.state == "PENDING" }
            return capture.confirmWithAccount(pending.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now), "测试银行（1234）")
        }
        assertEquals(debit, ledger.get(confirmed("one-bank"))!!.accountId)
        ledger.saveAccount("测试银行信用卡（1234）")
        assertNull(ledger.get(confirmed("ambiguous-bank"))!!.accountId)
        ledger.archiveAccount(debit)
        capture.capture(event("archived-bank"), proposal)
        val pending = capture.candidates.first().single { it.state == "PENDING" }
        val archived = capture.confirmWithAccount(pending.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now), "测试银行储蓄卡（1234）")
        assertNull(ledger.get(archived)!!.accountId)
        assertEquals(6, ledger.accounts.first().size)
    }
    @Test fun existingDirectSessionAndLegacyPendingAreReusedInConfirmModeWhenDirectionChanges() = runBlocking {
        enable()
        capture.capture(event("existing-session"), proposal)
        val original = capture.candidates.first().single().transactionId!!
        database.captureDao().insert(io.qingledger.data.database.NotificationCandidateEntity(
            "legacy-pending", source, "user-0", "existing-session", "legacy-update", 100, now, 1234, "EXPENSE", "UNVERIFIED_TEMPLATE",
        ))
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        capture.capture(event("existing-session", "refund-direction"), proposal.copy(kind = TransactionKind.REFUND))
        assertEquals(0, capture.pendingCount.first())
        assertEquals(1, database.ledgerDao().allTransactionsForBackup().size)
        assertTrue(capture.candidates.first().all { it.transactionId == original })
    }
    @Test fun confirmationModePersistsStructuredCandidateWithoutFinancialEffectsAndMergesUpdates() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        val accountCount = ledger.accounts.first().size
        val balances = ledger.accountBalances().first()
        val initial = event("a11y:pending", "initial")
        assertTrue(capture.capture(initial, proposal.copy(merchantName = "餐厅", accountName = "新银行储蓄卡（5678）", originalAmountMinor = 1500, discountMinor = 266)))
        val first = capture.candidates.first().single()
        assertEquals(first.id, capture.capturedCandidateId(initial))
        assertEquals("PENDING", first.state)
        assertEquals("餐厅", first.merchant)
        assertEquals("新银行储蓄卡（5678）", first.accountName)
        assertEquals(1500L, first.originalAmountMinor)
        assertEquals(266L, first.discountMinor)
        assertEquals(0, database.ledgerDao().allTransactionsForBackup().size)
        assertEquals(accountCount, ledger.accounts.first().size)
        assertEquals(balances, ledger.accountBalances().first())
        assertEquals(LedgerSummary(), ledger.summary(period).first())
        assertEquals("a11y:pending", capture.accessibilityReceipts("user-0").single().session)
        assertTrue(capture.capture(event("a11y:pending", "updated"), proposal.copy(amountMinor = 999, merchantName = "新商户")))
        assertEquals(first.id, capture.candidates.first().single().id)
        assertEquals(first.id, capture.capturedCandidateId(event("a11y:pending", "updated")))
        assertEquals(999L, capture.candidate(first.id).first()!!.amountMinor)
        assertEquals("新银行储蓄卡（5678）", capture.candidate(first.id).first()!!.accountName)
        assertNull(capture.candidate(first.id).first()!!.discountMinor)
        assertEquals(1, capture.pendingCount.first())
        assertFalse(capture.capture(event("a11y:pending", "updated"), proposal))
    }
    @Test fun bookingModeInvalidatesQueuedEventsButDailySummaryDoesNotAndOldPendingIsPreserved() = runBlocking {
        assertEquals(CaptureBookingMode.DIRECT, capture.settings.first().bookingMode)
        assertTrue(capture.settings.first().dailySummaryEnabled)
        enable()
        val queued = event("queued")
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        assertFalse(capture.capture(queued, proposal))
        val fresh = event("pending")
        capture.setDailySummaryEnabled(false)
        assertEquals(fresh.generation, capture.settings.first().generation)
        assertTrue(capture.capture(fresh, proposal))
        val id = capture.candidates.first().single().id
        capture.setBookingMode(CaptureBookingMode.DIRECT)
        assertTrue(capture.capture(event("pending", "after-switch"), proposal.copy(amountMinor = 900)))
        assertEquals("PENDING", capture.candidate(id).first()!!.state)
        assertEquals(0, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun editedConfirmationAndLaterSessionDirectionChangesNeverPostTwice() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        capture.capture(event("edited"), proposal)
        val pending = capture.candidates.first().single()
        val confirmedAt = now + 86400000L
        val id = capture.confirm(pending.id, TransactionDraft(TransactionKind.INCOME, 700, confirmedAt, merchant = "修正商户", accountId = "account-0", note = "用户备注"))
        capture.capture(event("edited", "refund"), proposal.copy(kind = TransactionKind.REFUND))
        capture.setBookingMode(CaptureBookingMode.DIRECT)
        capture.capture(event("edited", "original-expense"), proposal)
        assertEquals(1, database.ledgerDao().allTransactionsForBackup().size)
        assertEquals(700L, ledger.get(id)!!.amountMinor)
        assertEquals(TransactionKind.INCOME, ledger.get(id)!!.kind)
        assertEquals("account-0", ledger.get(id)!!.accountId)
        assertEquals("用户备注", ledger.get(id)!!.note)
        assertEquals(confirmedAt, ledger.get(id)!!.occurredAt)
    }
    @Test fun candidateLookupIncludesRecordsOutsideListWindowAndHiddenTombstones() = runBlocking {
        repeat(205) { index -> database.captureDao().insert(io.qingledger.data.database.NotificationCandidateEntity(
            "candidate-$index", source, "user-0", "session-$index", "digest-$index", 100, now + index, 1234, "EXPENSE", "UNVERIFIED_TEMPLATE", state = "IGNORED",
        )) }
        assertEquals(200, capture.candidates.first().size)
        assertEquals("candidate-0", capture.candidate("candidate-0").first()!!.id)
        capture.clearResolved()
        assertTrue(capture.candidates.first().isEmpty())
        assertEquals("IGNORED", capture.candidate("candidate-0").first()!!.state)
    }
    @Test fun concurrentIgnoreAndConfirmNeverCreatesMoreThanOneTransactionOrRevivesDismissedSession() = runBlocking {
        enable()
        capture.setBookingMode(CaptureBookingMode.CONFIRM)
        capture.capture(event("a11y:race"), proposal)
        val pending = capture.candidates.first().single()
        listOf(async(Dispatchers.IO) { runCatching { capture.confirm(pending.id, TransactionDraft(TransactionKind.EXPENSE, 1234, now)) } },
            async(Dispatchers.IO) { runCatching { capture.ignore(pending.id) } }).awaitAll()
        val finalState = capture.candidate(pending.id).first()!!.state
        assertTrue(finalState in setOf("IGNORED", "CONFIRMED"))
        assertEquals(if (finalState == "CONFIRMED") 1 else 0, database.ledgerDao().allTransactionsForBackup().size)
        capture.capture(event("a11y:race", "replayed"), proposal)
        assertEquals(0, capture.pendingCount.first())
        assertEquals(if (finalState == "CONFIRMED") 1 else 0, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun concurrentConfirmationOfLegacySessionUsesOneTransactionAcrossEditedDirections() = runBlocking {
        listOf("first", "second").forEach { id ->
            database.captureDao().insert(io.qingledger.data.database.NotificationCandidateEntity(
                id, source, "user-0", "shared-pending", id, 100, now, 1234, "EXPENSE", "UNVERIFIED_TEMPLATE",
            ))
        }
        val ids = listOf("first", "second").mapIndexed { index, id -> async(Dispatchers.IO) {
            capture.confirm(id, TransactionDraft(if (index == 0) TransactionKind.EXPENSE else TransactionKind.INCOME, 1234, now))
        } }.awaitAll()
        assertEquals(1, ids.distinct().size)
        assertEquals(1, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun ignoredPendingSessionStaysDiscardedAfterRevisitAndClear() = runBlocking {
        enable()
        database.captureDao().insert(io.qingledger.data.database.NotificationCandidateEntity(
            "discarded", source, "user-0", "a11y:discarded", "first", 100, now, 1234, "EXPENSE", "UNVERIFIED_TEMPLATE",
        ))
        capture.ignore("discarded")
        capture.ignore("discarded")
        capture.clearResolved()
        assertFalse(capture.capture(event("a11y:discarded", "revisit"), proposal))
        assertEquals(0, database.ledgerDao().allTransactionsForBackup().size)
        assertEquals("a11y:discarded", capture.accessibilityReceipts("user-0").single().session)
    }
    @Test fun explicitRefundConfirmationUsesEditedDraftAndIsIdempotent() = runBlocking {
        database.captureDao().insert(io.qingledger.data.database.NotificationCandidateEntity(
            "pending-refund", source, "user-0", "refund-session", "refund-digest", 100, now,
            1234, "REFUND", "REFUND_REVIEW",
        ))
        val confirmedAt = now + 86400000L
        val draft = TransactionDraft(TransactionKind.REFUND, 999, confirmedAt, merchant = "用户修改")
        val id = capture.confirm("pending-refund", draft)
        assertEquals(id, capture.confirm("pending-refund", draft.copy(kind = TransactionKind.INCOME)))
        val transaction = ledger.get(id)!!
        assertEquals(TransactionKind.REFUND, transaction.kind)
        assertEquals(999L, transaction.amountMinor)
        assertEquals(confirmedAt, transaction.occurredAt)
        assertEquals("用户修改", transaction.merchant)
        assertEquals(source, transaction.sourcePackage)
        assertEquals(1, capture.overview(PeriodRange(confirmedAt, confirmedAt + 1)).first().todayCount)
        assertEquals(0, capture.overview(PeriodRange(now, now + 1)).first().todayCount)
    }
    @Test fun legacyReceiptSeedsReplayAndDoesNotLoseItsAccountButFreshPaymentStillPosts() = runBlocking {
        enable()
        val legacy = "a11y:" + "a".repeat(64)
        capture.capture(event(legacy), proposal.copy(merchantName = "测试商户", accountName = "微信零钱"))
        val original = capture.candidates.first().single().transactionId!!
        val history = capture.accessibilityReceipts("user-0")
        assertEquals(1, history.size)
        assertTrue(capture.accessibilityReceipts("other-profile").isEmpty())
        val tracker = ReceiptSessionTracker("user-0") { "a11y:" + "b".repeat(64) }
        history.forEach { tracker.seed(it.source, it.kind, it.amountMinor, it.merchant, it.session) }
        val replay = tracker.success(source, proposal, "测试商户", 1, 1000)
        capture.capture(event(replay.session, "returned-without-account"), proposal.copy(merchantName = "测试商户"))
        assertEquals(1, database.ledgerDao().allTransactionsForBackup().size)
        assertNotNull(ledger.get(original)!!.accountId)
        tracker.confirmation(source, 1234, "测试商户", 1, 2000)
        val fresh = tracker.success(source, proposal, "测试商户", 1, 3000)
        capture.capture(event(fresh.session, "fresh"), proposal.copy(merchantName = "测试商户"))
        assertEquals(2, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun replayOfADeletedBillNeverRestoresOrRecreatesIt() = runBlocking {
        enable()
        val session = "a11y:" + "c".repeat(64)
        capture.capture(event(session), proposal.copy(merchantName = "测试商户"))
        val id = capture.candidates.first().single().transactionId!!
        ledger.delete(id, 0)
        assertEquals(session, capture.accessibilityReceipts("user-0").single().session)
        capture.capture(event(session, "revisited"), proposal)
        assertNull(ledger.get(id))
        assertEquals(1, database.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun bankWithoutSubtypeMatchesOneExistingCardButNeverGuessesBetweenTwo() = runBlocking {
        val first = ledger.saveAccount("测试银行储蓄卡（1234）")
        enable()
        capture.capture(event("untyped-one"), proposal.copy(accountName = "测试银行（1234）"))
        val firstCandidate = capture.candidates.first().single()
        assertEquals(first, ledger.get(firstCandidate.transactionId!!)!!.accountId)
        ledger.saveAccount("测试银行信用卡（1234）")
        capture.capture(event("untyped-two"), proposal.copy(accountName = "测试银行（1234）"))
        val latest = capture.candidates.first().first { it.id != firstCandidate.id }
        assertNull(ledger.get(latest.transactionId!!)!!.accountId)
    }
    @Test fun structuredMerchantAndAccountAreSavedOnlyWhenSuccessIsCaptured() = runBlocking {
        enable()
        val cache = OcrPaymentContextCache()
        cache.observe(source, capture.settings.first().generation, OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", OcrPaymentContext(1234, "测试商户", "测试银行储蓄卡（1234）")), 0)
        assertTrue(capture.candidates.first().isEmpty())
        assertEquals(0L, ledger.summary(period).first().expense)
        val context = cache.consume(source, capture.settings.first().generation, proposal, "测试商户", 1000)!!
        capture.capture(event(), proposal.copy(accountName = context.accountName, merchantName = context.merchant))
        val row = ledger.get(capture.candidates.first().single().transactionId!!)!!
        assertEquals("测试商户", row.merchant)
        assertEquals(ledger.accounts.first().single { it.name == "测试银行储蓄卡（1234）" }.id, row.accountId)
    }
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
    @Test fun overviewCountIncludesHiddenConfirmedRecordsAndIgnoresListLimit() = runBlocking {
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
        assertEquals(206, capture.overview(PeriodRange(now, now + 206 * 180000L)).first().todayCount)
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
