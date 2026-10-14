package io.qingledger.data.repository

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.data.database.MIGRATION_1_2
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class CaptureMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    @Test fun migrationPreservesExistingLedgerAndStartsCaptureOff() {
        val name = "capture-migration-test"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO books (id,name,currency,zoneId) VALUES ('default','Existing','CNY','Asia/Shanghai')")
            execSQL("""INSERT INTO transactions (id,bookId,kind,amountMinor,currency,occurredAt,zoneId,merchant,categoryId,accountId,fromAccountId,toAccountId,note,revision,createdAt,updatedAt,deletedAt) VALUES ('existing','default','EXPENSE',1234,'CNY',0,'Asia/Shanghai','Retained',NULL,NULL,NULL,NULL,'original',0,0,0,NULL)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).apply {
            query("SELECT amountMinor, merchant, sourcePackage FROM transactions WHERE id='existing'").use {
                assertTrue(it.moveToFirst()); assertEquals(1234L, it.getLong(0)); assertEquals("Retained", it.getString(1)); assertTrue(it.isNull(2))
            }
            query("SELECT enabled, generation FROM capture_configuration WHERE id=0").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(0L, it.getLong(1)) }
            close()
        }
    }
}
