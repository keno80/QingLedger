package io.qingledger.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.*
import io.qingledger.data.backup.RoomBackupRepository
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import io.qingledger.domain.export.*
import io.qingledger.domain.importing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*

class DiscountRepositoryTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    private fun test(block: suspend (LedgerDatabase, RoomLedgerRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LedgerDatabase::class.java).build()
        try { val repo = RoomLedgerRepository(db); repo.initialize(); block(db, repo) } finally { db.close() }
    }
    private val draft = TransactionDraft(TransactionKind.EXPENSE, 760, 1000, "优惠测试", accountId = "account-0", originalAmountMinor = 800, discountMinor = 40)
    @Test fun pricesSurviveSaveEditAndOnlyPaidAmountAffectsBalanceAndSummary() = test { _, repo ->
        val id = repo.save(draft)
        assertEquals(800L, repo.get(id)!!.originalAmountMinor)
        assertEquals(40L, repo.get(id)!!.discountMinor)
        assertEquals(-760L, repo.accountBalances().first().single { it.account.id == "account-0" }.movementMinor)
        assertEquals(LedgerSummary(0, 760), repo.summary(PeriodRange(0, 2000)).first())
        repo.save(draft.copy(note = "普通编辑"), id, 0)
        assertEquals(40L, repo.get(id)!!.discountMinor)
        repo.save(draft.copy(originalAmountMinor = null, discountMinor = null), id, 1)
        assertNull(repo.get(id)!!.originalAmountMinor)
        assertTrue(repo.changes(id).first().any { it.operation.contains("优惠") })
    }
    @Test fun captureWritesDiscountOnlyAtSuccessAndKeepsItOnDuplicate() = test { db, repo ->
        val capture = RoomCaptureRepository(db, repo)
        capture.setSource("com.tencent.mm", true); capture.setEnabled(true)
        val settings = capture.settings.first()
        val proposal = NotificationProposal(760, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE", "微信零钱", "优惠测试", 800, 40)
        val event = CapturedNotification("com.tencent.mm", "test", "a11y:discount", "first", 1000, null, settings.generation)
        capture.capture(event, proposal)
        capture.capture(event.copy(digest = "expanded"), proposal)
        val rows = db.ledgerDao().allTransactionsForBackup()
        assertEquals(1, rows.size)
        assertEquals(40L, repo.get(rows.single().id)!!.discountMinor)
    }
    @Test fun csvExportImportAndBackupRestoreKeepPrices() = test { db, repo ->
        val id = repo.save(draft)
        val exported = RoomCsvExportRepository(db).snapshot(CsvExportRequest(CsvExportScope.ALL)).single()
        assertEquals(800L, exported.originalAmountMinor)
        val input = exported.copy(id = "discount-import", category = "", account = "")
        val parsed = CsvImportParser.parse(StringWriter().also { LedgerCsv.write(it, listOf(input)) }.toString())
        val importer = RoomCsvImportRepository(db)
        assertEquals(1, importer.commit(importer.preview(parsed)).imported)
        assertEquals(40L, repo.get("discount-import")!!.discountMinor)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "discount-backup-${System.nanoTime()}")
        try {
            val backup = RoomBackupRepository(context, db, file, { BackupPreferences(ThemeMode.SYSTEM, CaptureMethod.ACCESSIBILITY) }, {})
            val bytes = backup.create()
            repo.save(draft.copy(originalAmountMinor = null, discountMinor = null), id, 0)
            backup.restore(bytes)
            assertEquals(800L, repo.get(id)!!.originalAmountMinor)
            assertEquals(40L, repo.get(id)!!.discountMinor)
        } finally { file.delete() }
    }
    @Test fun migrationFourToFivePreservesExistingDataAndAddsUnknownPrices() {
        val name = "discount-migration-test"
        migration.createDatabase(name, 4).apply {
            execSQL("INSERT INTO books(id,name,currency,zoneId) VALUES('default','原账本','CNY','Asia/Shanghai')")
            execSQL("INSERT INTO transactions(id,bookId,kind,amountMinor,currency,occurredAt,zoneId,merchant,categoryId,accountId,fromAccountId,toAccountId,note,revision,createdAt,updatedAt,deletedAt,sourcePackage,originalExpenseId) VALUES('old','default','EXPENSE',760,'CNY',0,'Asia/Shanghai','原商户',NULL,NULL,NULL,NULL,'原备注',2,1,2,NULL,'com.tencent.mm',NULL)")
            close()
        }
        migration.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).apply {
            query("SELECT amountMinor,merchant,note,revision,originalAmountMinor,discountMinor FROM transactions WHERE id='old'").use {
                assertTrue(it.moveToFirst()); assertEquals(760L, it.getLong(0)); assertEquals("原商户", it.getString(1)); assertEquals("原备注", it.getString(2)); assertEquals(2, it.getInt(3)); assertTrue(it.isNull(4)); assertTrue(it.isNull(5))
            }
            close()
        }
    }
    @Test fun oldBackupGetsUnknownPricesAndInvalidNewPricesAreRejectedBeforeRestore() = test { db, repo ->
        val id = repo.save(draft)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "discount-legacy-${System.nanoTime()}")
        try {
            val backup = RoomBackupRepository(context, db, file, { BackupPreferences(ThemeMode.SYSTEM, CaptureMethod.ACCESSIBILITY) }, {})
            val original = backup.create()
            fun altered(oldVersion: Boolean): ByteArray {
                val envelope = JSONObject(String(original, Charsets.UTF_8))
                val payload = JSONObject(envelope.getString("payload"))
                val table = payload.getJSONObject("tables").getJSONObject("transactions")
                val columns = table.getJSONArray("columns")
                val keep = (0 until columns.length()).filter { !oldVersion || columns.getString(it) !in setOf("originalAmountMinor", "discountMinor") }
                val rows = table.getJSONArray("rows")
                if (oldVersion) {
                    removeVersionSixCaptureColumns(payload)
                    table.put("columns", JSONArray().apply { keep.forEach { put(columns.get(it)) } })
                    for (index in 0 until rows.length()) { val row = rows.getJSONArray(index); rows.put(index, JSONArray().apply { keep.forEach { put(row.get(it)) } }) }
                    envelope.put("databaseVersion", 4)
                } else {
                    rows.getJSONArray(0).put((0 until columns.length()).single { columns.getString(it) == "discountMinor" }, 50)
                }
                val text = payload.toString()
                return envelope.put("payload", text).put("sha256", MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }).toString().toByteArray()
            }
            try { backup.restore(altered(false)); fail("inconsistent discount accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(40L, repo.get(id)!!.discountMinor)
            assertFalse(backup.hasRecovery())
            backup.restore(altered(true))
            assertNull(repo.get(id)!!.originalAmountMinor)
            assertNull(repo.get(id)!!.discountMinor)
            assertEquals(760L, repo.get(id)!!.amountMinor)
        } finally { file.delete() }
    }
}
