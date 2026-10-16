package io.qingledger.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.qingledger.data.database.*
import io.qingledger.domain.export.*
import io.qingledger.domain.model.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CsvExportRepositoryTest {
    @Test fun tooManyRowsAreRejectedWithoutTruncatingOrChangingLedger() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            RoomLedgerRepository(db).initialize()
            db.withTransaction {
                db.openHelper.writableDatabase.compileStatement("""INSERT INTO transactions
                    (id,bookId,kind,amountMinor,currency,occurredAt,zoneId,merchant,note,revision,createdAt,updatedAt)
                    VALUES (?,'default','EXPENSE',1,'CNY',0,'UTC','','',0,0,0)""").use { statement ->
                    repeat(CsvExportRepository.MAX_ROWS + 1) { index ->
                        statement.bindString(1, "limit-$index")
                        statement.executeInsert()
                    }
                }
            }
            try {
                RoomCsvExportRepository(db).snapshot(CsvExportRequest(CsvExportScope.ALL))
                fail("Must reject exports over the limit instead of silently truncating")
            } catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("50000")) }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transactions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(CsvExportRepository.MAX_ROWS + 1, cursor.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun exportsEntireRangeWithArchivedReferencesAndTransferButExcludesDeletedAndEndBoundary() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            val ledger = RoomLedgerRepository(db)
            ledger.initialize()
            val dao = db.ledgerDao()
            val start = Instant.parse("2026-09-30T16:00:00Z").toEpochMilli()
            val end = Instant.parse("2026-10-01T16:00:00Z").toEpochMilli()
            dao.seedBook(BookEntity("default", "日常账本", "CNY", "Asia/Shanghai"))
            db.openHelper.writableDatabase.execSQL("UPDATE books SET zoneId='Asia/Shanghai' WHERE id='default'")
            val expense = ledger.save(TransactionDraft(TransactionKind.EXPENSE, 1234, start, "商户", "expense-0", "account-0", note = "换行\n备注"))
            val transfer = ledger.save(TransactionDraft(TransactionKind.TRANSFER, 2500, end - 1, fromAccountId = "account-0", toAccountId = "account-1"))
            ledger.save(TransactionDraft(TransactionKind.INCOME, 999, end, accountId = "account-0"))
            val deleted = ledger.save(TransactionDraft(TransactionKind.EXPENSE, 1, start + 1))
            ledger.delete(deleted, 0)
            ledger.archiveCategory("expense-0")
            ledger.archiveAccount("account-0")
            val repo = RoomCsvExportRepository(db)
            val rows = repo.snapshot(CsvExportRequest(CsvExportScope.CUSTOM, "2026-10-01", "2026-10-01"))
            assertEquals(listOf(transfer, expense), rows.map { it.id })
            assertEquals("现金", rows.last().account)
            assertEquals("餐饮", rows.last().category)
            assertEquals("换行\n备注", rows.last().note)
            assertEquals("现金", rows.first().fromAccount)
            assertEquals("银行卡", rows.first().toAccount)
            assertEquals(3, repo.snapshot(CsvExportRequest(CsvExportScope.ALL)).size)
            assertEquals(4, dao.allTransactionsForBackup().size)
        } finally { db.close() }
    }

    @Test fun exportHasNoPaginationLimitAndInvalidDatesDoNotChangeLedger() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            val ledger = RoomLedgerRepository(db)
            ledger.initialize()
            repeat(55) { ledger.save(TransactionDraft(TransactionKind.EXPENSE, it + 1L, it.toLong())) }
            val repo = RoomCsvExportRepository(db)
            assertEquals(55, repo.snapshot(CsvExportRequest(CsvExportScope.ALL)).size)
            try {
                repo.snapshot(CsvExportRequest(CsvExportScope.CUSTOM, "2026-10-02", "2026-10-01"))
                fail("Should reject reversed dates")
            } catch (_: IllegalArgumentException) { }
            assertEquals(55, db.ledgerDao().allTransactionsForBackup().size)
        } finally { db.close() }
    }
}
