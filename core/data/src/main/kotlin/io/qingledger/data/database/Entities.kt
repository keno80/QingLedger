package io.qingledger.data.database

import androidx.room.*

@Entity(tableName = "books")
data class BookEntity(@PrimaryKey val id: String, val name: String, val currency: String, val zoneId: String)

@Entity(tableName = "categories", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"])], indices = [Index("bookId")])
data class CategoryEntity(@PrimaryKey val id: String, val bookId: String, val name: String, val kind: String, val archived: Boolean = false)

@Entity(tableName = "accounts", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"])], indices = [Index("bookId")])
data class AccountEntity(@PrimaryKey val id: String, val bookId: String, val name: String, val archived: Boolean = false, val openingBalanceMinor: Long? = null)

@Entity(tableName = "transactions", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"]),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"]),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["fromAccountId"]),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"]),
], indices = [Index(value = ["bookId", "occurredAt"]), Index("categoryId"), Index("accountId"), Index("fromAccountId"), Index("toAccountId")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAt: Long,
    val zoneId: String,
    val merchant: String,
    val categoryId: String?,
    val accountId: String?,
    val fromAccountId: String?,
    val toAccountId: String?,
    val note: String,
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val sourcePackage: String? = null,
)

@Entity(tableName = "transaction_edits", foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"])], indices = [Index("transactionId")])
data class EditEntity(@PrimaryKey val id: String, val transactionId: String, val revision: Int, val operation: String, val createdAt: Long)

data class SummaryRow(val income: Long, val expense: Long)
data class AccountBalanceRow(val id: String, val name: String, val archived: Boolean, val openingBalanceMinor: Long?, val movementMinor: Long)
