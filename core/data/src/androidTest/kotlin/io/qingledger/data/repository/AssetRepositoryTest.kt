package io.qingledger.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.*
import io.qingledger.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class AssetRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var store: RoomLedgerRepository
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LedgerDatabase::class.java).build()
        store = RoomLedgerRepository(db)
        store.initialize()
    }
    @After fun teardown() { db.close() }

    @Test fun openingAndAllHistoryFlowsIncludeBothTransferLegs() = runBlocking {
        store.saveAccountDetails("现金", 10000, "account-0")
        store.saveAccountDetails("银行卡", -2000, "account-1")
        store.save(TransactionDraft(TransactionKind.INCOME, 1000, 0, accountId = "account-0"))
        store.save(TransactionDraft(TransactionKind.EXPENSE, 500, 1, accountId = "account-0"))
        store.save(TransactionDraft(TransactionKind.TRANSFER, 3000, 2, fromAccountId = "account-0", toAccountId = "account-1"))
        store.save(TransactionDraft(TransactionKind.INCOME, 999, 3))
        val rows = store.accountBalances().first()
        assertEquals(7500L, rows.first { it.account.id == "account-0" }.balanceMinor)
        assertEquals(1000L, rows.first { it.account.id == "account-1" }.balanceMinor)
        assertNull(rows.first { it.account.id == "account-2" }.balanceMinor)
        assertEquals(8500L, AssetRules.summarize(rows).netMinor)
    }
    @Test fun editingDeletingAndUndoingRecalculateBalance() = runBlocking {
        store.saveAccountDetails("现金", 10000, "account-0")
        val draft = TransactionDraft(TransactionKind.EXPENSE, 100, 0, accountId = "account-0")
        val id = store.save(draft)
        store.save(draft.copy(amountMinor = 200, accountId = "account-1"), id, 0)
        assertEquals(10000L, store.accountBalances().first().first { it.account.id == "account-0" }.balanceMinor)
        assertEquals(-200L, store.accountBalances().first().first { it.account.id == "account-1" }.movementMinor)
        val token = store.delete(id, 1)
        assertEquals(0L, store.accountBalances().first().first { it.account.id == "account-1" }.movementMinor)
        store.undo(token)
        assertEquals(-200L, store.accountBalances().first().first { it.account.id == "account-1" }.movementMinor)
    }
    @Test fun renameAndArchiveKeepOpeningAndUnknownCanBeRestored() = runBlocking {
        store.saveAccountDetails("现金", 0, "account-0")
        store.saveAccount("备用现金", "account-0")
        store.archiveAccount("account-0")
        assertEquals(0L, store.accountBalances().first().first { it.account.id == "account-0" }.balanceMinor)
        store.restoreAccount("account-0")
        store.saveAccountDetails("备用现金", null, "account-0")
        assertNull(store.accounts.first().first { it.id == "account-0" }.openingBalanceMinor)
    }
    @Test fun invalidOpeningCannotPartiallyRenameAccount() = runBlocking {
        try { store.saveAccountDetails("Changed", Long.MAX_VALUE, "account-0"); fail("invalid opening accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("现金", store.accounts.first().first { it.id == "account-0" }.name)
    }
    @Test fun migrationPreservesLedgerAndMarksExistingBalancesUnknown() {
        val name = "asset-migration-test"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO books VALUES ('default','Existing','CNY','Asia/Shanghai')")
            execSQL("INSERT INTO accounts VALUES ('cash','default','Cash',0)")
            execSQL("""INSERT INTO transactions (id,bookId,kind,amountMinor,currency,occurredAt,zoneId,merchant,categoryId,accountId,fromAccountId,toAccountId,note,revision,createdAt,updatedAt,deletedAt,sourcePackage) VALUES ('kept','default','EXPENSE',1234,'CNY',0,'Asia/Shanghai','Retained',NULL,'cash',NULL,NULL,'original',0,0,0,NULL,NULL)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).apply {
            query("SELECT openingBalanceMinor,name FROM accounts WHERE id='cash'").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals("Cash", it.getString(1)) }
            query("SELECT amountMinor,accountId FROM transactions WHERE id='kept'").use { assertTrue(it.moveToFirst()); assertEquals(1234L, it.getLong(0)); assertEquals("cash", it.getString(1)) }
            close()
        }
    }
}
