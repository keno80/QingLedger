package io.qingledger.data.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.withTransaction
import io.qingledger.data.database.*
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomBackupRepository(
    context: Context,
    private val database: LedgerDatabase,
    recoveryFile: File,
    private val readPreferences: suspend () -> BackupPreferences,
    private val writePreferences: suspend (BackupPreferences) -> Unit,
) : BackupRepository {
    private val app = context.applicationContext
    private val recoveryFile = AtomicFile(recoveryFile)
    private val mutex = Mutex()

    override fun hasRecovery(): Boolean = recoveryFile.baseFile.exists()
    override suspend fun recovery(): ByteArray? = withContext(Dispatchers.IO) { mutex.withLock {
        if (hasRecovery()) recoveryFile.openRead().use { it.readBytes() } else null
    } }
    override suspend fun create(): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        val preferences = readPreferences()
        val snapshot = database.withTransaction { snapshot(preferences) }
        validate(snapshot)
        BackupCodec.encode(snapshot)
    } }
    override suspend fun inspect(bytes: ByteArray): BackupInfo = withContext(Dispatchers.IO) { mutex.withLock {
        prepare(bytes).info()
    } }

    override suspend fun restore(bytes: ByteArray): Unit = withContext(Dispatchers.IO + NonCancellable) { mutex.withLock {
        // Validate in an isolated Room database before touching the live database or preferences.
        val imported = prepare(bytes)
        val previousPreferences = readPreferences()
        database.withTransaction {
            val previous = BackupCodec.encode(snapshot(previousPreferences))
            val stream = recoveryFile.startWrite()
            try { stream.write(previous); recoveryFile.finishWrite(stream) }
            catch (error: Exception) { recoveryFile.failWrite(stream); throw error }
            database.captureDao().seed(CaptureConfiguration())
            database.captureDao().setEnabled(false)
        }
        try {
            // Preference persistence must succeed before committing the financial-data replacement.
            writePreferences(imported.preferences)
            database.withTransaction {
                val oldGeneration = database.captureDao().configuration()?.generation ?: 0
                val importedGeneration = imported.tables.getValue("capture_configuration").let { table ->
                    table.rows.firstOrNull()?.get(table.columns.indexOfFirst { it.name == "generation" }) as? Long ?: 0L
                }
                replace(database, imported)
                database.captureDao().seed(CaptureConfiguration())
                database.openHelper.writableDatabase.execSQL("UPDATE capture_configuration SET enabled=0, generation=? WHERE id=0", arrayOf(Math.addExact(maxOf(oldGeneration, importedGeneration), 1)))
            }
        } catch (error: Exception) {
            try { writePreferences(previousPreferences) } catch (_: Exception) { /* Recovery file retains the previous settings. */ }
            throw IllegalStateException("恢复未完成，账单未替换，采集保持暂停。恢复前备份已保留。", error)
        }
    } }

    private suspend fun prepare(bytes: ByteArray): BackupSnapshot {
        val columns = database.withTransaction { columns(database) }
        val snapshot = BackupCodec.decode(bytes, columns)
        validate(snapshot)
        return snapshot
    }
    private fun columns(db: LedgerDatabase): Map<String, List<BackupColumn>> = backupTables.associateWith { name ->
        db.openHelper.writableDatabase.query("PRAGMA table_info(`$name`)").use { cursor -> buildList {
            while (cursor.moveToNext()) add(BackupColumn(cursor.getString(1), cursor.getString(2) == "INTEGER", cursor.getInt(3) == 0))
        } }
    }
    private fun snapshot(preferences: BackupPreferences): BackupSnapshot {
        val columns = columns(database)
        var total = 0
        val tables = backupTables.associateWith { name ->
            val fields = columns.getValue(name)
            val rows = database.openHelper.writableDatabase.query("SELECT ${fields.joinToString { "`${it.name}`" }} FROM `$name` ORDER BY 1").use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    total++; require(total <= 200000) { "记录过多，当前版本暂不支持备份。" }
                    add(fields.mapIndexed { index, column -> if (cursor.isNull(index)) null else if (column.integer) cursor.getLong(index) else cursor.getString(index) })
                }
            } }
            BackupTable(fields, rows)
        }
        return BackupSnapshot(System.currentTimeMillis(), preferences, tables)
    }
    private suspend fun validate(snapshot: BackupSnapshot) {
        snapshot.tables.values.forEach { table ->
            table.columns.forEachIndexed { index, column -> if (column.name in setOf("enabled", "archived", "hidden")) require(table.rows.all { it[index] == 0L || it[index] == 1L }) { "备份开关状态无效。" } }
        }
        val staged = Room.inMemoryDatabaseBuilder(app, LedgerDatabase::class.java).build()
        try {
            staged.withTransaction {
                replace(staged, snapshot)
                val dao = staged.ledgerDao()
                val book = dao.book()
                require(snapshot.tables.getValue("books").rows.size == 1 && book.id == "default" && book.currency == "CNY") { "备份账本格式不支持。" }
                ZoneId.of(book.zoneId)
                val categories = dao.allCategories()
                val accounts = dao.allAccounts()
                require(categories.all { ReferenceRules.normalizeName(it.name) != null && it.kind in setOf("EXPENSE", "INCOME") }) { "备份分类无效。" }
                require(accounts.all { ReferenceRules.normalizeName(it.name) != null && (it.openingBalanceMinor == null || it.openingBalanceMinor in -LedgerRules.MAX_AMOUNT..LedgerRules.MAX_AMOUNT) }) { "备份账户无效。" }
                require(categories.map { it.kind to it.name.trim().lowercase(java.util.Locale.ROOT) }.distinct().size == categories.size && accounts.map { it.name.trim().lowercase(java.util.Locale.ROOT) }.distinct().size == accounts.size) { "备份存在重复分类或账户。" }
                val categoryKinds = categories.associate { it.id to it.kind }
                dao.allTransactionsForBackup().forEach { row ->
                    val kind = TransactionKind.valueOf(row.kind)
                    require(row.currency == "CNY" && row.revision >= 0) { "备份账单无效。" }
                    ZoneId.of(row.zoneId)
                    require(LedgerRules.validate(TransactionDraft(kind, row.amountMinor, row.occurredAt, row.merchant, row.categoryId, row.accountId, row.fromAccountId, row.toAccountId, row.note)) == null) { "备份账单内容无效。" }
                    require(row.categoryId == null || categoryKinds[row.categoryId] == row.kind) { "备份分类关联无效。" }
                }
                staged.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "备份关联数据不完整。" } }
                val config = staged.captureDao().configuration()
                val configurations = snapshot.tables.getValue("capture_configuration").rows
                require(configurations.size <= 1 && (configurations.isEmpty() || config != null) && (config == null || config.generation in 0..Long.MAX_VALUE - 1024)) { "备份采集配置无效。" }
                staged.openHelper.writableDatabase.query("SELECT COUNT(*) FROM notification_candidates WHERE state NOT IN ('PENDING','CONFIRMED','AUTO_POSTED','MERGED','SKIPPED','IGNORED') OR (kind IS NOT NULL AND kind NOT IN ('EXPENSE','INCOME','TRANSFER')) OR (transactionId IS NOT NULL AND transactionId NOT IN (SELECT id FROM transactions)) OR (relatedTransactionId IS NOT NULL AND relatedTransactionId NOT IN (SELECT id FROM transactions))").use { require(it.moveToFirst() && it.getLong(0) == 0L) { "备份识别记录无效。" } }
            }
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("备份数据不完整或关联校验失败。") }
        finally { staged.close() }
    }
    private fun replace(db: LedgerDatabase, snapshot: BackupSnapshot) {
        val sql = db.openHelper.writableDatabase
        backupTables.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
        backupTables.forEach { name ->
            val table = snapshot.tables.getValue(name)
            val insert = "INSERT INTO `$name` (${table.columns.joinToString { "`${it.name}`" }}) VALUES (${table.columns.joinToString { "?" }})"
            table.rows.forEach { sql.execSQL(insert, it.toTypedArray()) }
        }
    }
}
