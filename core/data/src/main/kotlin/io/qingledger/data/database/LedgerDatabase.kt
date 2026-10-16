package io.qingledger.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BookEntity::class, CategoryEntity::class, AccountEntity::class, TransactionEntity::class, EditEntity::class, CaptureConfiguration::class, CaptureSource::class, NotificationCandidateEntity::class], version = 3, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun captureDao(): CaptureDao
    companion object {
        fun create(context: Context): LedgerDatabase = Room.databaseBuilder(
            context.applicationContext, LedgerDatabase::class.java, "ledger.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
