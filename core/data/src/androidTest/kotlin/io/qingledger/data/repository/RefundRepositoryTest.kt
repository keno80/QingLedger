package io.qingledger.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.*
import io.qingledger.data.backup.RoomBackupRepository
import io.qingledger.domain.model.*
import io.qingledger.domain.importing.*
import io.qingledger.domain.export.*
import io.qingledger.domain.repository.*
import java.io.File
import java.io.StringWriter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

class RefundRepositoryTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    private fun test(block: suspend (LedgerDatabase, RoomLedgerRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try { val repo = RoomLedgerRepository(db); repo.initialize(); block(db, repo) } finally { db.close() }
    }
    private fun expense() = TransactionDraft(TransactionKind.EXPENSE, 10000, 1000, "商户", "expense-0", "account-0")
    private fun refund(id: String?, amount: Long = 3000) = TransactionDraft(TransactionKind.REFUND, amount, 2000, "退款", "expense-0", "account-0", originalExpenseId = id)
    private suspend fun fails(block: suspend () -> Unit) { try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} }

    @Test fun balancesAndPeriodSummariesUseNetExpenseAndRefundDate() = test { _, repo ->
        val id = repo.save(expense(), null, null); repo.save(refund(id), null, null)
        val all = repo.summary(PeriodRange(0, 3000)).first()
        assertEquals(0L, all.income); assertEquals(7000L, all.expense)
        assertEquals(-3000L, repo.summary(PeriodRange(2000, 3000)).first().expense)
        assertEquals(-7000L, repo.accountBalances().first().single { it.account.id == "account-0" }.movementMinor)
    }
    @Test fun preventsOverRefundInvalidLinksAndInconsistentCategory() = test { _, repo ->
        val id = repo.save(expense(), null, null); repo.save(refund(id), null, null)
        fails { repo.save(refund(id, 7001), null, null) }
        fails { repo.save(refund("missing"), null, null) }
        fails { repo.save(refund(id).copy(categoryId = "expense-1"), null, null) }
        fails { repo.save(refund(id).copy(occurredAt = 0), null, null) }
        assertEquals(7000L, repo.summary(PeriodRange(0, 3000)).first().expense)
    }
    @Test fun linkedOriginalEditsAndDeletionAreGuardedAndUnlinkAllowsDeletion() = test { _, repo ->
        val id = repo.save(expense(), null, null)
        val refundId = repo.save(refund(id), null, null)
        fails { repo.delete(id, 0) }
        fails { repo.save(expense().copy(amountMinor = 2000), id, 0) }
        fails { repo.save(expense().copy(kind = TransactionKind.INCOME, categoryId = "income-0"), id, 0) }
        repo.save(refund(null), refundId, 0)
        val token = repo.delete(id, 0)
        assertNull(repo.get(id)); repo.undo(token); assertNotNull(repo.get(id))
    }
    @Test fun importChecksRefundsAsABatchAndPreservesLinksOnExportAndBackup() = test { db, repo ->
        val importer = RoomCsvImportRepository(db)
        val original = LedgerCsvRow("original", TransactionKind.EXPENSE, 10000, 1000, "Asia/Shanghai", "CNY", "餐饮", "现金", "", "", "商户", "", "com.tencent.mm")
        val returned = original.copy(id = "refund", kind = TransactionKind.REFUND, amountMinor = 3000, occurredAt = 2000, originalExpenseId = "original")
        fun parsed(rows: List<LedgerCsvRow>) = CsvImportParser.parse(StringWriter().also { LedgerCsv.write(it, rows) }.toString())
        val plan = importer.preview(parsed(listOf(returned, original)))
        assertEquals(2, plan.ready); assertEquals(2, importer.commit(plan).imported)
        assertEquals(2, importer.preview(plan.file).duplicates)
        val over = importer.preview(parsed(listOf(returned.copy(id = "over", amountMinor = 7001))))
        assertEquals(1, over.invalid); assertFalse(over.canImport)
        assertEquals("original", RoomCsvExportRepository(db).snapshot(CsvExportRequest(CsvExportScope.ALL)).single { it.kind == TransactionKind.REFUND }.originalExpenseId)
        var prefs = BackupPreferences(ThemeMode.SYSTEM, CaptureMethod.ACCESSIBILITY)
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "refund-recovery-test-${System.nanoTime()}")
        try {
            val backup = RoomBackupRepository(ApplicationProvider.getApplicationContext(), db, file, { prefs }, { prefs = it })
            val bytes = backup.create(); assertEquals(2, backup.inspect(bytes).transactionCount)
            repo.delete("refund", 0); backup.restore(bytes)
            assertEquals("original", repo.get("refund")!!.originalExpenseId)
            assertEquals(7000L, repo.summary(PeriodRange(0, 3000)).first().expense)
        } finally { file.delete() }
    }
    @Test fun nativeXlsxParserReadsExcelDateSharedStringsAndLongIdsExactly() = test { _, _ ->
        val headers = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注")
        val data = listOf("46311.5", "商户消费", "模拟商户", "商品", "支出", "12.34", "零钱", "支付成功", "4200000000000000000000000001", "/", "/")
        fun cell(col: Int, row: Int, v: String, number: Boolean = false) = "<c r=\"${('A'.code + col).toChar()}$row\"${if (number) "" else " t=\"inlineStr\""}>${if (number) "<v>$v</v>" else "<is><t>$v</t></is>"}</c>"
        val sheet = "<worksheet><sheetData><row r=\"18\">${headers.mapIndexed { i, s -> cell(i, 18, s) }.joinToString("")}</row><row r=\"19\">${data.mapIndexed { i, s -> cell(i, 19, s, i == 0 || i == 5) }.joinToString("")}</row></sheetData></worksheet>"
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> listOf("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to sheet).forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } } }.toByteArray()
        val parsed = PlatformBillParser.parse(bytes, ImportSource.WECHAT)
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty()); assertEquals(1, parsed.totalRows)
        assertEquals(1234L, parsed.records.single().amountMinor)
        assertEquals("2026-10-16T04:00:00Z", java.time.Instant.ofEpochMilli(parsed.records.single().occurredAt).toString())
        assertEquals(PlatformBillParser.stableId(ImportSource.WECHAT, data[8]), parsed.records.single().id)
    }
    @Test fun migrationThreeToFourPreservesEveryExistingValue() {
        val name = "refund-migration-test"
        migration.createDatabase(name, 3).apply {
            execSQL("INSERT INTO books(id,name,currency,zoneId) VALUES('default','原账本','CNY','Asia/Shanghai')")
            execSQL("INSERT INTO transactions(id,bookId,kind,amountMinor,currency,occurredAt,zoneId,merchant,categoryId,accountId,fromAccountId,toAccountId,note,revision,createdAt,updatedAt,deletedAt,sourcePackage) VALUES('old','default','EXPENSE',1234,'CNY',0,'Asia/Shanghai','原商户',NULL,NULL,NULL,NULL,'原备注',2,1,2,NULL,'com.tencent.mm')")
            close()
        }
        migration.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).apply {
            query("SELECT amountMinor,merchant,note,revision,sourcePackage,originalExpenseId FROM transactions WHERE id='old'").use { assertTrue(it.moveToFirst()); assertEquals(1234L,it.getLong(0)); assertEquals("原商户",it.getString(1)); assertEquals("原备注",it.getString(2)); assertEquals(2,it.getInt(3)); assertEquals("com.tencent.mm",it.getString(4)); assertTrue(it.isNull(5)) }
            close()
        }
    }
    @Test fun versionThreeBackupRestoresIntoVersionFourWithoutInventingRefundLinks() = test { db, repo ->
        repo.save(expense(), null, null)
        var prefs = BackupPreferences(ThemeMode.SYSTEM, CaptureMethod.ACCESSIBILITY)
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "legacy-refund-test-${System.nanoTime()}")
        try {
            val backup = RoomBackupRepository(ApplicationProvider.getApplicationContext(), db, file, { prefs }, { prefs = it })
            val envelope = JSONObject(String(backup.create(), Charsets.UTF_8))
            val payload = JSONObject(envelope.getString("payload"))
            val table = payload.getJSONObject("tables").getJSONObject("transactions")
            val columns = table.getJSONArray("columns")
            val removed = (0 until columns.length()).filter { columns.getString(it) in setOf("originalExpenseId", "originalAmountMinor", "discountMinor") }
            val oldColumns = JSONArray(); (0 until columns.length()).filter { it !in removed }.forEach { oldColumns.put(columns.get(it)) }
            table.put("columns", oldColumns)
            val rows = table.getJSONArray("rows")
            for (rowIndex in 0 until rows.length()) {
                val row = rows.getJSONArray(rowIndex); val old = JSONArray()
                (0 until row.length()).filter { it !in removed }.forEach { old.put(row.get(it)) }; rows.put(rowIndex,old)
            }
            val text = payload.toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val bytes = envelope.put("databaseVersion",3).put("payload",text).put("sha256",digest).toString().toByteArray()
            assertEquals(1, backup.inspect(bytes).transactionCount)
            backup.restore(bytes)
            assertTrue(db.ledgerDao().allTransactionsForBackup().all { it.originalExpenseId == null })
            assertEquals(10000L, repo.summary(PeriodRange(0,3000)).first().expense)
        } finally { file.delete() }
    }
    @Test fun refundUndoAndBulkImportCannotExceedOriginalAmount() = test { db, repo ->
        val id = repo.save(expense(), null, null)
        val returned = repo.save(refund(id), null, null)
        val deleted = repo.delete(returned,0)
        repo.save(refund(id,10000),null,null)
        fails { repo.undo(deleted) }
        assertNull(repo.get(returned))
        val row = LedgerCsvRow("new-over",TransactionKind.REFUND,1,3000,"Asia/Shanghai","CNY","餐饮","现金","","","商户","","",id)
        val parsed = CsvImportParser.parse(StringWriter().also { LedgerCsv.write(it,listOf(row)) }.toString())
        val importer = RoomCsvImportRepository(db)
        val plan = importer.preview(parsed)
        assertEquals(1,plan.invalid)
        assertEquals(0,importer.commit(plan).imported)
        assertEquals(0L,repo.summary(PeriodRange(0,4000)).first().expense)
    }
    @Test fun invalidRefundGroupDoesNotRejectAnUnrelatedValidRefund() = test { db, repo ->
        val first = repo.save(expense(), null, null); val second = repo.save(expense().copy(merchant = "另一商户"), null, null)
        val row = LedgerCsvRow("group-a-1", TransactionKind.REFUND, 6000, 2000, "Asia/Shanghai", "CNY", "餐饮", "现金", "", "", "商户", "", "", first)
        val rows = listOf(row, row.copy(id = "group-a-2"), row.copy(id = "group-b", amountMinor = 5000, originalExpenseId = second))
        val parsed = CsvImportParser.parse(StringWriter().also { LedgerCsv.write(it, rows) }.toString())
        val importer = RoomCsvImportRepository(db); val plan = importer.preview(parsed)
        assertEquals(2,plan.invalid); assertEquals(1,plan.ready)
        val result = importer.commit(plan); assertEquals(1,result.imported); assertEquals(2,result.failed)
        assertEquals(15000L,repo.summary(PeriodRange(0,3000)).first().expense)
    }
}
