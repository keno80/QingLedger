package io.qingledger.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.domain.export.*
import io.qingledger.domain.importing.*
import io.qingledger.domain.model.*
import java.io.StringWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CsvImportRepositoryTest {
    private fun row(id: String = "csv-new") = LedgerCsvRow(id, TransactionKind.EXPENSE, 1234, 0, "Asia/Shanghai", "CNY", "餐饮", "现金", "", "", "CSV商户", "=原始备注", "com.tencent.mm")
    private fun file(vararg rows: LedgerCsvRow) = CsvImportParser.parse(StringWriter().also { LedgerCsv.write(it, rows.asIterable()) }.toString())
    private fun test(block: suspend (LedgerDatabase, RoomLedgerRepository, RoomCsvImportRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try { val ledger = RoomLedgerRepository(db); ledger.initialize(); block(db, ledger, RoomCsvImportRepository(db)) }
        finally { db.close() }
    }

    @Test fun previewDoesNotWriteAndConfirmedImportKeepsIdTimeTextAndHistory() = test { db, _, repo ->
        val plan = repo.preview(file(row()))
        assertTrue(plan.canImport)
        assertEquals(0, db.ledgerDao().allTransactionsForBackup().size)
        assertEquals(1, repo.commit(plan).imported)
        val tx = db.ledgerDao().get("csv-new")!!
        assertEquals(1234L, tx.amountMinor); assertEquals(0L, tx.occurredAt)
        assertEquals("Asia/Shanghai", tx.zoneId); assertEquals("=原始备注", tx.note)
        assertEquals("expense-0", tx.categoryId); assertEquals("account-0", tx.accountId)
        db.openHelper.readableDatabase.query("SELECT operation FROM transaction_edits WHERE transactionId='csv-new'").use { assertTrue(it.moveToFirst()); assertEquals("IMPORT_CSV", it.getString(0)) }
    }
    @Test fun duplicateFilesAndDeletedIdsNeverOverwriteOrResurrect() = test { db, ledger, repo ->
        val parsed = file(row(), row())
        val plan = repo.preview(parsed)
        assertEquals(1, plan.ready); assertEquals(1, plan.duplicates)
        repo.commit(plan)
        ledger.delete("csv-new", 0)
        val again = repo.preview(parsed)
        assertEquals(0, again.ready); assertEquals(2, again.duplicates)
        assertEquals(0, repo.commit(again).imported)
        assertNotNull(db.ledgerDao().get("csv-new")!!.deletedAt)
        assertEquals(1, db.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun resolvesUnknownReferencesCreatesAccountsWithUnknownBalanceAndImportsTransfer() = test { db, _, repo ->
        val parsed = file(row().copy(category = "餐费", account = "新钱包"), row("csv-transfer").copy(kind = TransactionKind.TRANSFER, category = "", account = "", fromAccount = "新钱包", toAccount = "银行卡"))
        val initial = repo.preview(parsed)
        assertFalse(initial.canImport)
        val choices = initial.mappings.filter { it.choice.action == CsvMappingAction.UNRESOLVED }.associate { it.key to CsvMappingChoice(CsvMappingAction.CREATE) }
        val plan = repo.preview(parsed, choices)
        assertEquals(2, plan.ready)
        repo.commit(plan)
        val account = db.ledgerDao().allAccounts().single { it.name == "新钱包" }
        assertNull(account.openingBalanceMinor)
        assertEquals(account.id, db.ledgerDao().get("csv-transfer")!!.fromAccountId)
    }
    @Test fun archivedMatchRequiresExplicitRestoreAndConcurrentArchiveRejectsCommit() = test { db, ledger, repo ->
        val plan = repo.preview(file(row()))
        ledger.archiveAccount("account-0")
        try { repo.commit(plan); fail("Must revalidate archived account") } catch (_: IllegalStateException) { }
        assertNull(db.ledgerDao().get("csv-new"))
        val fresh = repo.preview(file(row()))
        val key = fresh.mappings.single { it.type == CsvReferenceType.ACCOUNT }.key
        val resolved = repo.preview(fresh.file, mapOf(key to CsvMappingChoice(CsvMappingAction.RESTORE, "account-0")))
        repo.commit(resolved)
        assertFalse(db.ledgerDao().account("account-0")!!.archived)
    }
    @Test fun databaseFailureRollsBackNewReferencesTransactionsAndHistory() = test { db, _, repo ->
        val parsed = file(row().copy(account = "新钱包"), row("blocked").copy(account = "新钱包"))
        val initial = repo.preview(parsed)
        val plan = repo.preview(parsed, initial.mappings.filter { it.choice.action == CsvMappingAction.UNRESOLVED }.associate { it.key to CsvMappingChoice(CsvMappingAction.CREATE) })
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_csv BEFORE INSERT ON transactions WHEN NEW.id='blocked' BEGIN SELECT RAISE(ABORT, 'test'); END")
        try { repo.commit(plan); fail("Expected insert failure") } catch (_: Exception) { }
        assertEquals(0, db.ledgerDao().allTransactionsForBackup().size)
        assertEquals(4, db.ledgerDao().allAccounts().size)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transaction_edits").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }
    @Test fun commitRechecksDuplicateAndSameTransferTargetIsInvalid() = test { db, _, repo ->
        val plan = repo.preview(file(row()))
        repo.commit(plan)
        assertEquals(1, repo.commit(plan).skipped)
        val transfer = file(row("transfer").copy(kind = TransactionKind.TRANSFER, category = "", account = "", fromAccount = "现金", toAccount = "银行卡"))
        val pending = repo.preview(transfer)
        val choices = pending.mappings.associate { it.key to CsvMappingChoice(CsvMappingAction.EXISTING, "account-0") }
        val invalid = repo.preview(transfer, choices)
        assertEquals(1, invalid.invalid); assertFalse(invalid.canImport)
        assertEquals(1, db.ledgerDao().allTransactionsForBackup().size)
    }
    @Test fun conflictingIdsAreRejectedAndOptionalMappingsCanBeCleared() = test { db, _, repo ->
        val parsed = file(row("conflict"), row("conflict").copy(amountMinor = 100), row().copy(category = "未知分类", account = "未知账户"), row("zero").copy(amountMinor = 0))
        val pending = repo.preview(parsed)
        assertEquals(3, pending.invalid)
        val plan = repo.preview(parsed, pending.mappings.associate { it.key to CsvMappingChoice(CsvMappingAction.NONE) })
        assertEquals(1, plan.ready)
        val result = repo.commit(plan)
        assertEquals(1, result.imported); assertEquals(3, result.failed)
        val imported = db.ledgerDao().get("csv-new")!!
        assertNull(imported.categoryId); assertNull(imported.accountId)
        assertNull(db.ledgerDao().get("conflict"))
    }
}
