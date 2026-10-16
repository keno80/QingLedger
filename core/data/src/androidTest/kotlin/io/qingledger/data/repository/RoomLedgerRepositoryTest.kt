package io.qingledger.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.LedgerConflict
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RoomLedgerRepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private val now = Instant.parse("2026-10-13T06:00:00Z").toEpochMilli()
    private val period = LedgerRules.period(YearMonth.of(2026, 10), ZoneId.of("Asia/Shanghai"))
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        repository = RoomLedgerRepository(database, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
        repository.initialize()
    }
    @After fun teardown() { database.close() }

    @Test fun statisticsReadsWholeMonthAndExcludesDeletedAndOutsidePeriod() = runBlocking {
        repeat(101) { repository.save(TransactionDraft(TransactionKind.EXPENSE, 100, now, categoryId = "expense-0")) }
        val deleted = repository.save(TransactionDraft(TransactionKind.EXPENSE, 500, now))
        repository.delete(deleted, 0)
        repository.save(TransactionDraft(TransactionKind.EXPENSE, 900, period.end))
        repository.save(TransactionDraft(TransactionKind.EXPENSE, 900, period.start - 1))
        repository.save(TransactionDraft(TransactionKind.REFUND, 200, now, categoryId = "expense-0"))
        val rows = repository.monthlyTransactions(period).first()
        assertEquals(102, rows.size)
        assertEquals(9900L, StatisticsRules.categories(rows).single().amountMinor)
        assertEquals(repository.summary(period).first(), LedgerRules.summarize(rows))
    }

    @Test fun deletionAndUndoChangeSummaryAtomically() = runBlocking {
        val id = repository.save(TransactionDraft(TransactionKind.EXPENSE, 1234, now))
        assertEquals(1234L, repository.summary(period).first().expense)
        val token = repository.delete(id, repository.get(id)!!.revision)
        assertNull(repository.get(id))
        assertEquals(0L, repository.summary(period).first().expense)
        repository.undo(token)
        assertEquals(1234L, repository.summary(period).first().expense)
        assertNotNull(repository.get(id))
    }
    @Test fun staleEditorCannotOverwriteLatestAmount() = runBlocking {
        val draft = TransactionDraft(TransactionKind.EXPENSE, 100, now)
        val id = repository.save(draft)
        repository.save(draft.copy(amountMinor = 200), id, 0)
        try { repository.save(draft.copy(amountMinor = 300), id, 0); fail("stale update accepted") }
        catch (_: LedgerConflict) { }
        assertEquals(200L, repository.get(id)!!.amountMinor)
    }
    @Test fun invalidAccountDoesNotLeavePartialTransaction() = runBlocking {
        try { repository.save(TransactionDraft(TransactionKind.EXPENSE, 100, now, accountId = "missing")); fail("invalid account accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0L, repository.summary(period).first().expense)
    }
    @Test fun transferDoesNotChangeIncomeExpense() = runBlocking {
        repository.save(TransactionDraft(TransactionKind.INCOME, 10000, now))
        repository.save(TransactionDraft(TransactionKind.EXPENSE, 2500, now))
        repository.save(TransactionDraft(TransactionKind.TRANSFER, 3000, now, fromAccountId = "account-0", toAccountId = "account-1"))
        val summary = repository.summary(period).first()
        assertEquals(10000L, summary.income)
        assertEquals(2500L, summary.expense)
        assertEquals(7500L, summary.balance)
    }
    @Test fun archivedReferencesRemainReadableAndCannotBeNewlyAssigned() = runBlocking {
        val draft = TransactionDraft(TransactionKind.EXPENSE, 100, now, categoryId = "expense-0", accountId = "account-0")
        val id = repository.save(draft)
        repository.archiveCategory("expense-0")
        repository.archiveAccount("account-0")
        repository.initialize()
        assertTrue(repository.accounts.first().first { it.id == "account-0" }.archived)
        assertNotNull(repository.get(id))
        repository.save(draft.copy(amountMinor = 200), id, 0)
        try { repository.save(draft); fail("archived references accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun fileDatabaseSurvivesCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qingledger-persistence-test.db"
        context.deleteDatabase(name)
        var persistent = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        try {
            var store = RoomLedgerRepository(persistent)
            store.initialize()
            val id = store.save(TransactionDraft(TransactionKind.EXPENSE, 1234, now, merchant = "Persistence test"))
            persistent.close()
            persistent = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            store = RoomLedgerRepository(persistent)
            assertEquals(1234L, store.get(id)!!.amountMinor)
        } finally { persistent.close(); context.deleteDatabase(name) }
    }
    @Test fun customReferencesCanBeRenamedArchivedAndRestoredWithoutLosingLinks() = runBlocking {
        val account = repository.saveAccount("  Travel wallet  ")
        val category = repository.saveCategory("旅行", TransactionKind.EXPENSE)
        val id = repository.save(TransactionDraft(TransactionKind.EXPENSE, 100, now, categoryId = category, accountId = account))
        repository.saveAccount("Trip wallet", account)
        repository.saveCategory("出行", TransactionKind.EXPENSE, category)
        repository.archiveAccount(account)
        repository.archiveCategory(category)
        repository.restoreAccount(account)
        repository.restoreCategory(category)
        repository.initialize()
        assertEquals(account, repository.get(id)!!.accountId)
        assertEquals(category, repository.get(id)!!.categoryId)
        assertEquals("Trip wallet", repository.accounts.first().first { it.id == account }.name)
        assertFalse(repository.categories.first().first { it.id == category }.archived)
    }
    @Test fun duplicateAndInvalidReferenceNamesCannotBeCreated() = runBlocking {
        val id = repository.saveAccount("Travel")
        repository.archiveAccount(id)
        try { repository.saveAccount(" travel "); fail("archived duplicate accepted") } catch (_: IllegalArgumentException) { }
        try { repository.saveCategory("餐饮", TransactionKind.EXPENSE); fail("duplicate category accepted") } catch (_: IllegalArgumentException) { }
        try { repository.saveCategory("Transfer", TransactionKind.TRANSFER); fail("transfer category accepted") } catch (_: IllegalArgumentException) { }
        try { repository.saveAccount("\n"); fail("invalid name accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(5, repository.accounts.first().size)
    }
    @Test fun historyShowsChangedFieldsAndDeletionRestoreOrder() = runBlocking {
        val draft = TransactionDraft(TransactionKind.EXPENSE, 100, now)
        val id = repository.save(draft)
        repository.save(draft.copy(amountMinor = 200, note = "Updated"), id, 0)
        val deletion = repository.delete(id, 1)
        repository.undo(deletion)
        val history = repository.changes(id).first()
        assertEquals(listOf("RESTORE", "DELETE", "EDIT:金额、备注", "CREATE"), history.map { it.operation })
        assertEquals(listOf(3, 2, 1, 0), history.map { it.revision })
    }
}
