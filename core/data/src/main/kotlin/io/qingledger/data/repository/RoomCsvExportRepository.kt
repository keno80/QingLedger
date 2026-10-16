package io.qingledger.data.repository

import androidx.room.withTransaction
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.domain.export.*
import io.qingledger.domain.model.TransactionKind
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomCsvExportRepository(private val database: LedgerDatabase) : CsvExportRepository {
    override suspend fun snapshot(request: CsvExportRequest): List<LedgerCsvRow> = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.ledgerDao()
            val period = request.period(ZoneId.of(dao.book().zoneId))
            val rows = dao.csvExport(period?.start, period?.end, CsvExportRepository.MAX_ROWS + 1)
            require(rows.size <= CsvExportRepository.MAX_ROWS) { "范围内超过 50000 笔账单，请缩小日期范围后重试。" }
            rows.map { row ->
                val tx = row.transaction
                LedgerCsvRow(tx.id, TransactionKind.valueOf(tx.kind), tx.amountMinor, tx.occurredAt, tx.zoneId, tx.currency,
                    row.categoryName.orEmpty(), row.accountName.orEmpty(), row.fromAccountName.orEmpty(), row.toAccountName.orEmpty(),
                    tx.merchant, tx.note, tx.sourcePackage.orEmpty(), tx.originalExpenseId.orEmpty())
            }
        }
    }
}
