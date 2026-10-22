package io.qingledger.data.repository

import androidx.room.withTransaction
import io.qingledger.data.database.*
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomCaptureRepository(
    private val database: LedgerDatabase, private val ledger: LedgerRepository,
    private val allowedSources: Set<String> = setOf("com.eg.android.AlipayGphone", "com.tencent.mm"),
) : CaptureRepository {
    private val dao = database.captureDao()
    override val settings = combine(dao.observeConfiguration(), dao.observeSources()) { config, sources ->
        CaptureSettings(config?.enabled ?: false, config?.generation ?: 0, sources.filter { it.enabled && it.sourcePackage in allowedSources }.map { it.sourcePackage }.toSet())
    }
    override val candidates = dao.candidates().map { rows -> rows.map { record -> record.candidate.let { PendingCandidate(it.id, it.sourcePackage, it.versionCode, it.receivedAt, it.amountMinor, it.kind?.let(TransactionKind::valueOf), it.reason, it.state, it.transactionId, it.relatedTransactionId, record.merchantName, record.accountName) } } }
    override val pendingCount = dao.pendingCount()
    override fun overview(period: PeriodRange) = combine(dao.postedCount(period.start, period.end), dao.latest()) { count, latest ->
        val row = latest?.candidate
        CaptureOverview(count, row?.let { PendingCandidate(it.id, it.sourcePackage, it.versionCode, it.receivedAt, it.amountMinor, it.kind?.let(TransactionKind::valueOf), it.reason, it.state, it.transactionId, it.relatedTransactionId) }, latest?.merchantName)
    }
    override suspend fun capturedState(event: CapturedNotification): String? = dao.replay(event.sessionKey, event.digest)?.state
    override suspend fun setEnabled(enabled: Boolean) = database.withTransaction {
        dao.seed(CaptureConfiguration())
        dao.setEnabled(enabled)
    }
    override suspend fun setSource(source: String, enabled: Boolean) = database.withTransaction {
        require(source in allowedSources) { "不支持该来源。" }
        dao.seed(CaptureConfiguration())
        dao.setSource(CaptureSource(source, enabled))
        dao.nextGeneration()
    }
    override suspend fun capture(event: CapturedNotification, proposal: NotificationProposal): Boolean = database.withTransaction {
        val config = dao.configuration() ?: return@withTransaction false
        if (!config.enabled || config.generation != event.generation || event.sourcePackage !in allowedSources || dao.source(event.sourcePackage)?.enabled != true) return@withTransaction false
        if (dao.replay(event.sessionKey, event.digest) != null) return@withTransaction false
        val previous = dao.previous(event.sessionKey)
        val amount = proposal.amountMinor
        val kind = proposal.kind
        val related = previous?.transactionId ?: previous?.relatedTransactionId ?: if (amount != null && kind != null)
            dao.similar(amount, kind.name, event.receivedAt - 120000, event.receivedAt + 120000) else null
        val candidateId = UUID.randomUUID().toString()
        dao.insert(NotificationCandidateEntity(candidateId, event.sourcePackage, event.profileKey, event.sessionKey, event.digest, event.versionCode, event.receivedAt, proposal.amountMinor, proposal.kind?.name,
            if (proposal.reason in setOf("REFUND_REVIEW", "UNSUPPORTED_CURRENCY")) proposal.reason else if (related != null) "POSSIBLE_DUPLICATE" else if (previous != null) "NOTIFICATION_UPDATE" else proposal.reason,
            relatedTransactionId = related))
        val canPost = amount != null && amount in 1..LedgerRules.MAX_AMOUNT &&
            kind in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.REFUND) && proposal.reason == "UNVERIFIED_TEMPLATE"
        val sessionTransaction = dao.sessionTransactionByKind(event.sessionKey, kind?.name)
        when {
            !canPost -> dao.resolve(candidateId, "SKIPPED", null)
            sessionTransaction != null -> dao.resolve(candidateId, "MERGED", sessionTransaction)
            else -> {
                ledger.initialize()
                val accountId = proposal.accountName?.let(PaymentAccountRules::canonicalName)?.let { name ->
                    val accounts = database.ledgerDao().allAccounts()
                    val exact = accounts.filter { it.name.equals(name, ignoreCase = true) }
                    val identity = PaymentAccountRules.bankIdentity(name)
                    val matches = if (exact.isNotEmpty() || identity == null) exact else accounts.filter {
                        PaymentAccountRules.bankIdentity(it.name) == identity &&
                            (PaymentAccountRules.unspecifiedBankType(name) || PaymentAccountRules.unspecifiedBankType(it.name))
                    }
                    when {
                        matches.size == 1 && !matches.single().archived -> matches.single().id
                        matches.isNotEmpty() -> null
                        else -> ledger.saveAccount(name)
                    }
                }
                val transactionId = ledger.save(TransactionDraft(kind!!, amount!!, event.receivedAt,
                    merchant = proposal.merchantName?.trim()?.take(128).orEmpty(),
                    accountId = accountId,
                    note = if (event.sessionKey.startsWith("a11y:")) "自动采集：无障碍，时间为页面识别时间。" else ""))
                dao.markSource(transactionId, event.sourcePackage)
                dao.resolve(candidateId, "AUTO_POSTED", transactionId)
            }
        }
        true
    }
    override suspend fun confirm(id: String, draft: TransactionDraft): String = database.withTransaction {
        val candidate = dao.get(id) ?: throw IllegalArgumentException("事件不存在。")
        if (candidate.state in setOf("CONFIRMED", "AUTO_POSTED")) return@withTransaction candidate.transactionId!!
        require(candidate.state == "PENDING") { "该事件已处理。" }
        require(candidate.reason !in setOf("REFUND_REVIEW", "UNSUPPORTED_CURRENCY")) { "退款或其他币种请通过原账单人工处理。" }
        val transactionId = ledger.save(draft)
        dao.markSource(transactionId, candidate.sourcePackage)
        dao.resolve(id, "CONFIRMED", transactionId)
        transactionId
    }
    override suspend fun ignore(id: String) = database.withTransaction {
        val candidate = dao.get(id) ?: throw IllegalArgumentException("事件不存在。")
        require(candidate.state == "PENDING") { "该事件已处理。" }
        dao.resolve(id, "IGNORED", null)
    }
    override suspend fun merge(id: String, transactionId: String) = database.withTransaction {
        val candidate = dao.get(id) ?: throw IllegalArgumentException("事件不存在。")
        require(candidate.state == "PENDING" && candidate.relatedTransactionId == transactionId) { "无法关联该账单。" }
        requireNotNull(ledger.get(transactionId)) { "关联账单已删除。" }
        dao.resolve(id, "MERGED", transactionId)
    }
    override suspend fun clearResolved() = database.withTransaction { dao.hideResolved() }
}
