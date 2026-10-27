package io.qingledger.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN sourcePackage TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS capture_configuration (id INTEGER NOT NULL, enabled INTEGER NOT NULL, generation INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("INSERT OR IGNORE INTO capture_configuration (id, enabled, generation) VALUES (0, 0, 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS capture_sources (sourcePackage TEXT NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY(sourcePackage))")
        db.execSQL("""CREATE TABLE IF NOT EXISTS notification_candidates (id TEXT NOT NULL, sourcePackage TEXT NOT NULL, profileKey TEXT NOT NULL, sessionKey TEXT NOT NULL, digest TEXT NOT NULL, versionCode INTEGER, receivedAt INTEGER NOT NULL, amountMinor INTEGER, kind TEXT, reason TEXT NOT NULL, state TEXT NOT NULL, transactionId TEXT, relatedTransactionId TEXT, hidden INTEGER NOT NULL, PRIMARY KEY(id))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notification_candidates_sessionKey_digest ON notification_candidates (sessionKey, digest)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_candidates_state_receivedAt ON notification_candidates (state, receivedAt)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE capture_configuration ADD COLUMN bookingMode TEXT NOT NULL DEFAULT 'DIRECT'")
        db.execSQL("ALTER TABLE capture_configuration ADD COLUMN dailySummaryEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE notification_candidates ADD COLUMN merchant TEXT")
        db.execSQL("ALTER TABLE notification_candidates ADD COLUMN accountName TEXT")
        db.execSQL("ALTER TABLE notification_candidates ADD COLUMN originalAmountMinor INTEGER")
        db.execSQL("ALTER TABLE notification_candidates ADD COLUMN discountMinor INTEGER")
    }
}
