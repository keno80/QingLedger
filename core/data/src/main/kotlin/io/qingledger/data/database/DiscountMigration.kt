package io.qingledger.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN originalAmountMinor INTEGER")
        db.execSQL("ALTER TABLE transactions ADD COLUMN discountMinor INTEGER")
    }
}
