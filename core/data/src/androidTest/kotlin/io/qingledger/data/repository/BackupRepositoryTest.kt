package io.qingledger.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.backup.RoomBackupRepository
import io.qingledger.data.database.*
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class BackupRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var backup: RoomBackupRepository
    private lateinit var recoveryFile: File
    private var preferences = BackupPreferences(ThemeMode.DARK, CaptureMethod.ACCESSIBILITY)
    private var failPreferences = false
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        recoveryFile = File(context.cacheDir, "backup-test-${java.util.UUID.randomUUID()}.qlbackup")
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        ledger = RoomLedgerRepository(db)
        ledger.initialize()
        db.captureDao().seed(CaptureConfiguration(enabled = true, generation = 7))
        backup = RoomBackupRepository(context, db, recoveryFile, { preferences }, { if (failPreferences && it.theme == ThemeMode.DARK) throw java.io.IOException("test failure"); preferences = it })
    }
    @After fun teardown() { db.close(); recoveryFile.delete(); File(recoveryFile.path + ".bak").delete() }
    private suspend fun keptTransaction(): String {
        ledger.saveAccountDetails("现金", 10000, "account-0")
        return ledger.save(TransactionDraft(TransactionKind.EXPENSE, 1234, 0, "餐厅", accountId = "account-0", note = "备注,包含换行\n与引号\""))
    }
    private fun mutate(bytes: ByteArray, edit: (JSONObject) -> Unit): ByteArray {
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = JSONObject(envelope.getString("payload"))
        edit(payload)
        val json = payload.toString()
        envelope.put("payload", json).put("sha256", MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
        return envelope.toString().toByteArray()
    }
    private suspend fun rejected(bytes: ByteArray) {
        val before = ledger.allCount()
        try { backup.restore(bytes); fail("invalid backup accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(before, ledger.allCount())
        assertFalse(backup.hasRecovery())
        assertTrue(db.captureDao().configuration()!!.enabled)
    }
    private suspend fun RoomLedgerRepository.allCount(): Int = db.ledgerDao().allTransactionsForBackup().size

    @Test fun roundTripRestoresAllLedgerFieldsAndPreferencesAndKeepsRecovery() = runBlocking {
        val id = keptTransaction()
        db.captureDao().setSource(CaptureSource("com.tencent.mm", true))
        db.captureDao().insert(NotificationCandidateEntity("event", "com.tencent.mm", "p", "s", "d", 1, 0, 1234, "EXPENSE", "UNVERIFIED_TEMPLATE", "AUTO_POSTED", id))
        val bytes = backup.create()
        assertEquals(1, backup.inspect(bytes).transactionCount)
        ledger.save(TransactionDraft(TransactionKind.INCOME, 500, 1))
        ledger.saveAccountDetails("Changed", -100, "account-0")
        preferences = BackupPreferences(ThemeMode.LIGHT, CaptureMethod.ROOT)
        backup.restore(bytes)
        assertEquals(1, db.ledgerDao().allTransactionsForBackup().size)
        assertEquals("备注,包含换行\n与引号\"", ledger.get(id)!!.note)
        assertEquals(10000L, ledger.accounts.first().first { it.id == "account-0" }.openingBalanceMinor)
        assertEquals(8766L, ledger.accountBalances().first().first { it.account.id == "account-0" }.balanceMinor)
        assertEquals(id, db.captureDao().get("event")!!.transactionId)
        assertTrue(db.captureDao().source("com.tencent.mm")!!.enabled)
        assertFalse(db.captureDao().configuration()!!.enabled)
        assertTrue(db.captureDao().configuration()!!.generation > 7)
        assertEquals(BackupPreferences(ThemeMode.DARK, CaptureMethod.ACCESSIBILITY), preferences)
        assertEquals(2, backup.inspect(backup.recovery()!!).transactionCount)
    }
    @Test fun corruptedChecksumAndTruncatedFilesDoNotTouchLiveData() = runBlocking {
        keptTransaction()
        val bytes = backup.create()
        val corrupt = JSONObject(String(bytes)).put("sha256", "0000").toString().toByteArray()
        rejected(corrupt)
        rejected(bytes.copyOf(bytes.size / 2))
    }
    @Test fun unsupportedVersionAndOversizedFilesAreRejected() = runBlocking {
        val bytes = JSONObject(String(backup.create())).put("databaseVersion", 99).toString().toByteArray()
        rejected(bytes)
        rejected(ByteArray(BackupRepository.MAX_BYTES + 1))
    }
    @Test fun checksumValidBrokenReferencesAreRejectedBeforeReplacement() = runBlocking {
        keptTransaction()
        val bad = mutate(backup.create()) { root ->
            val table = root.getJSONObject("tables").getJSONObject("transactions")
            val columns = table.getJSONArray("columns")
            val account = (0 until columns.length()).first { columns.getString(it) == "accountId" }
            table.getJSONArray("rows").getJSONArray(0).put(account, "missing")
        }
        rejected(bad)
    }
    @Test fun checksumValidInvalidMoneyAndInjectedColumnsAreRejected() = runBlocking {
        keptTransaction()
        val bytes = backup.create()
        rejected(mutate(bytes) { root ->
            val table = root.getJSONObject("tables").getJSONObject("transactions")
            val columns = table.getJSONArray("columns")
            val amount = (0 until columns.length()).first { columns.getString(it) == "amountMinor" }
            table.getJSONArray("rows").getJSONArray(0).put(amount, 0)
        })
        rejected(mutate(bytes) { it.getJSONObject("tables").getJSONObject("accounts").getJSONArray("columns").put(0, "id); DROP TABLE transactions;--") })
    }
    @Test fun preferenceFailureDoesNotReplaceFinancialData() = runBlocking {
        keptTransaction()
        val bytes = backup.create()
        ledger.save(TransactionDraft(TransactionKind.INCOME, 100, 1))
        preferences = BackupPreferences(ThemeMode.LIGHT, CaptureMethod.ACCESSIBILITY)
        failPreferences = true
        try { backup.restore(bytes); fail("preference error ignored") } catch (_: IllegalStateException) { }
        assertEquals(2, db.ledgerDao().allTransactionsForBackup().size)
        assertEquals(ThemeMode.LIGHT, preferences.theme)
        assertFalse(db.captureDao().configuration()!!.enabled)
        assertTrue(backup.hasRecovery())
    }
    @Test fun failedDatabaseWriteRollsBackEveryTableAndPreferences() = runBlocking {
        val id = keptTransaction()
        val bytes = backup.create()
        val extra = ledger.save(TransactionDraft(TransactionKind.INCOME, 100, 1))
        preferences = BackupPreferences(ThemeMode.LIGHT, CaptureMethod.ACCESSIBILITY)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON transactions BEGIN SELECT RAISE(ABORT, 'test restore failure'); END")
        try { backup.restore(bytes); fail("write failure ignored") } catch (_: IllegalStateException) { }
        assertNotNull(ledger.get(id)); assertNotNull(ledger.get(extra))
        assertEquals(ThemeMode.LIGHT, preferences.theme)
        assertFalse(db.captureDao().configuration()!!.enabled)
        assertTrue(backup.hasRecovery())
    }
    @Test fun historyAndDeletedTransactionsSurviveBackup() = runBlocking {
        val id = keptTransaction()
        val token = ledger.delete(id, 0)
        val before = ledger.changes(id).first()
        val bytes = backup.create()
        ledger.undo(token)
        backup.restore(bytes)
        assertNull(ledger.get(id))
        assertEquals(before, ledger.changes(id).first())
        assertEquals(1, db.ledgerDao().allTransactionsForBackup().size)
    }
}
