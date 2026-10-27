package io.qingledger.data.repository

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.data.database.MIGRATION_1_2
import io.qingledger.data.database.MIGRATION_5_6
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class CaptureMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    @Test fun versionFiveKeepsCaptureSettingsAndCandidatesWithDirectBookingDefaults() {
        val name = "capture-confirmation-migration-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO capture_configuration (id,enabled,generation) VALUES (0,1,9)")
            execSQL("INSERT INTO notification_candidates (id,sourcePackage,profileKey,sessionKey,digest,receivedAt,amountMinor,kind,reason,state,hidden) VALUES ('existing','com.tencent.mm','p','s','d',100,1234,'EXPENSE','UNVERIFIED_TEMPLATE','IGNORED',1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6).apply {
            query("SELECT enabled,generation,bookingMode,dailySummaryEnabled FROM capture_configuration").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(9L, it.getLong(1)); assertEquals("DIRECT", it.getString(2)); assertEquals(1, it.getInt(3))
            }
            query("SELECT state,hidden,merchant,accountName,originalAmountMinor,discountMinor FROM notification_candidates WHERE id='existing'").use {
                assertTrue(it.moveToFirst()); assertEquals("IGNORED", it.getString(0)); assertEquals(1, it.getInt(1))
                (2..5).forEach { index -> assertTrue(it.isNull(index)) }
            }
            close()
        }
    }
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
