package io.qingledger.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("""SELECT t.*, c.name AS categoryName, a.name AS accountName,
        f.name AS fromAccountName, dest.name AS toAccountName
        FROM transactions t LEFT JOIN categories c ON c.id=t.categoryId
        LEFT JOIN accounts a ON a.id=t.accountId
        LEFT JOIN accounts f ON f.id=t.fromAccountId
        LEFT JOIN accounts dest ON dest.id=t.toAccountId
        WHERE t.bookId='default' AND t.deletedAt IS NULL
        AND (:start IS NULL OR t.occurredAt >= :start) AND (:end IS NULL OR t.occurredAt < :end)
        ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit""")
    suspend fun csvExport(start: Long?, end: Long?, limit: Int): List<CsvExportRow>

    @Query("SELECT * FROM transactions") suspend fun allTransactionsForBackup(): List<TransactionEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedBook(book: BookEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedCategories(categories: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedAccounts(accounts: List<AccountEntity>)
    @Query("SELECT * FROM books WHERE id = 'default'") suspend fun book(): BookEntity
    @Query("SELECT * FROM categories WHERE bookId = 'default' ORDER BY rowid") fun categories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM accounts WHERE bookId = 'default' ORDER BY rowid") fun accounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun category(id: String): CategoryEntity?
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountEntity?
    @Query("SELECT * FROM categories WHERE bookId = 'default'") suspend fun allCategories(): List<CategoryEntity>
    @Query("SELECT * FROM accounts WHERE bookId = 'default'") suspend fun allAccounts(): List<AccountEntity>
    @Insert suspend fun addCategory(category: CategoryEntity)
    @Insert suspend fun addAccount(account: AccountEntity)
    @Query("UPDATE categories SET name = :name WHERE id = :id") suspend fun renameCategory(id: String, name: String)
    @Query("UPDATE accounts SET name = :name WHERE id = :id") suspend fun renameAccount(id: String, name: String)
    @Query("UPDATE accounts SET openingBalanceMinor = :amount WHERE id = :id") suspend fun setOpeningBalance(id: String, amount: Long?)
    @Query("UPDATE categories SET archived = 0 WHERE id = :id") suspend fun restoreCategory(id: String)
    @Query("UPDATE accounts SET archived = 0 WHERE id = :id") suspend fun restoreAccount(id: String)
    @Query("SELECT * FROM transaction_edits WHERE transactionId = :id ORDER BY revision DESC, createdAt DESC") fun edits(id: String): Flow<List<EditEntity>>
    @Query("UPDATE categories SET archived = 1 WHERE id = :id") suspend fun archiveCategory(id: String)
    @Query("UPDATE accounts SET archived = 1 WHERE id = :id") suspend fun archiveAccount(id: String)

    @Query("""SELECT * FROM transactions WHERE bookId = 'default' AND deletedAt IS NULL
        AND occurredAt >= :start AND occurredAt < :end
        AND (:kind IS NULL OR kind = :kind)
        AND (:category IS NULL OR categoryId = :category)
        AND (:account IS NULL OR accountId = :account OR fromAccountId = :account OR toAccountId = :account)
        AND (:query = '' OR instr(lower(merchant || ' ' || note), lower(:query)) > 0)
        ORDER BY occurredAt DESC, id DESC LIMIT :limit""")
    fun transactions(start: Long, end: Long, query: String, kind: String?, category: String?, account: String?, limit: Int): Flow<List<TransactionEntity>>

    @Query("""SELECT COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amountMinor ELSE 0 END), 0) AS income,
        COALESCE(SUM(CASE WHEN kind = 'EXPENSE' THEN amountMinor ELSE 0 END), 0) AS expense
        FROM transactions WHERE bookId = 'default' AND deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end""")
    fun summary(start: Long, end: Long): Flow<SummaryRow>

    @Query("SELECT occurredAt FROM transactions WHERE bookId = 'default' AND deletedAt IS NULL")
    fun recordedTimes(): Flow<List<Long>>

    @Query("""SELECT a.id, a.name, a.archived, a.openingBalanceMinor,
        COALESCE(SUM(CASE
            WHEN t.kind = 'INCOME' AND t.accountId = a.id THEN t.amountMinor
            WHEN t.kind = 'EXPENSE' AND t.accountId = a.id THEN -t.amountMinor
            WHEN t.kind = 'TRANSFER' AND t.fromAccountId = a.id THEN -t.amountMinor
            WHEN t.kind = 'TRANSFER' AND t.toAccountId = a.id THEN t.amountMinor
            ELSE 0 END), 0) AS movementMinor
        FROM accounts a LEFT JOIN transactions t ON t.bookId = a.bookId AND t.deletedAt IS NULL
            AND (t.accountId = a.id OR t.fromAccountId = a.id OR t.toAccountId = a.id)
        WHERE a.bookId = 'default' GROUP BY a.id ORDER BY a.rowid""")
    fun accountBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: String): TransactionEntity?
    @Insert suspend fun insert(transaction: TransactionEntity)
    @Update suspend fun update(transaction: TransactionEntity)
    @Insert suspend fun log(edit: EditEntity)
}
