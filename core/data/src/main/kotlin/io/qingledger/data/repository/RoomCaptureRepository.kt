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
        CaptureSettings(config?.enabled ?: false, config?.generation ?: 0, sources.filter { it.enabled && it.sourcePackage in allowedSources }.map { it.sourcePackage }.toSet(),
            config?.bookingMode?.let(CaptureBookingMode::valueOf) ?: CaptureBookingMode.DIRECT, config?.dailySummaryEnabled ?: true)
    }
    override val candidates = dao.candidates().map { rows -> rows.map { it.domain() } }
    override fun candidate(id: String) = dao.observeCandidate(id).map { it?.domain() }
    override val pendingCount = dao.pendingCount()
    override fun overview(period: PeriodRange) = combine(dao.postedCount(period.start, period.end), dao.latest()) { count, latest ->
        CaptureOverview(count, latest?.domain(), latest?.merchantName)
    }
    override suspend fun capturedState(event: CapturedNotification): String? = dao.replay(event.sessionKey, event.digest)?.state
    override suspend fun capturedCandidateId(event: CapturedNotification): String? = dao.replay(event.sessionKey, event.digest)?.id
    override suspend fun accessibilityReceipts(profile: String) = dao.accessibilityReceipts(profile).map {
        StoredAccessibilityReceipt(it.source, TransactionKind.valueOf(it.kind), it.amountMinor, it.merchant, it.session)
    }
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
    override suspend fun setBookingMode(mode: CaptureBookingMode) = database.withTransaction {
        dao.seed(CaptureConfiguration())
        dao.setBookingMode(mode.name)
    }
    override suspend fun setDailySummaryEnabled(enabled: Boolean) = database.withTransaction {
        dao.seed(CaptureConfiguration())
        dao.setDailySummaryEnabled(enabled)
    }
    override suspend fun capture(event: CapturedNotification, proposal: NotificationProposal): Boolean = database.withTransaction {
        val config = dao.configuration() ?: return@withTransaction false
        if (!config.enabled || config.generation != event.generation || event.sourcePackage !in allowedSources || dao.source(event.sourcePackage)?.enabled != true) return@withTransaction false
        if (dao.replay(event.sessionKey, event.digest) != null) return@withTransaction false
        if (dao.isIgnored(event.sessionKey)) return@withTransaction false
        val previous = dao.previous(event.sessionKey)
        val amount = proposal.amountMinor
        val kind = proposal.kind
        val related = previous?.transactionId ?: previous?.relatedTransactionId ?: if (amount != null && kind != null)
            dao.similar(amount, kind.name, event.receivedAt - 120000, event.receivedAt + 120000) else null
        val canPost = amount != null && amount in 1..LedgerRules.MAX_AMOUNT &&
            kind in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.REFUND) && proposal.reason == "UNVERIFIED_TEMPLATE"
        val pending = dao.pending(event.sessionKey)
        val sessionTransaction = if (pending != null || config.bookingMode == CaptureBookingMode.CONFIRM.name) dao.sessionTransaction(event.sessionKey)
            else dao.confirmedTransaction(event.sessionKey) ?: dao.sessionTransactionByKind(event.sessionKey, kind?.name)
        if (pending != null && canPost) {
            dao.update(pending.copy(digest = event.digest, receivedAt = event.receivedAt, versionCode = event.versionCode,
                amountMinor = amount, kind = kind?.name, merchant = proposal.merchantName?.trim()?.take(128)?.takeIf { it.isNotBlank() } ?: pending.merchant,
                accountName = proposal.accountName ?: pending.accountName,
                originalAmountMinor = proposal.originalAmountMinor ?: pending.originalAmountMinor.takeIf { amount == pending.amountMinor && kind?.name == pending.kind },
                discountMinor = proposal.discountMinor ?: pending.discountMinor.takeIf { amount == pending.amountMinor && kind?.name == pending.kind },
                state = if (sessionTransaction == null) "PENDING" else "MERGED", transactionId = sessionTransaction))
            return@withTransaction true
        }
        val candidateId = UUID.randomUUID().toString()
        dao.insert(NotificationCandidateEntity(candidateId, event.sourcePackage, event.profileKey, event.sessionKey, event.digest, event.versionCode, event.receivedAt, proposal.amountMinor, proposal.kind?.name,
            if (proposal.reason in setOf("REFUND_REVIEW", "UNSUPPORTED_CURRENCY")) proposal.reason else if (related != null) "POSSIBLE_DUPLICATE" else if (previous != null) "NOTIFICATION_UPDATE" else proposal.reason,
            relatedTransactionId = related, merchant = proposal.merchantName?.trim()?.take(128), accountName = proposal.accountName,
            originalAmountMinor = proposal.originalAmountMinor, discountMinor = proposal.discountMinor))
        when {
            !canPost -> dao.resolve(candidateId, "SKIPPED", null)
            sessionTransaction != null -> dao.resolve(candidateId, "MERGED", sessionTransaction)
            config.bookingMode == CaptureBookingMode.CONFIRM.name -> Unit
            else -> {
                ledger.initialize()
                val accountId = recognizedAccount(proposal.accountName)
                val transactionId = ledger.save(TransactionDraft(kind!!, amount!!, event.receivedAt,
                    merchant = proposal.merchantName?.trim()?.take(128).orEmpty(),
                    originalAmountMinor = proposal.originalAmountMinor, discountMinor = proposal.discountMinor,
                    accountId = accountId,
                    note = if (event.sessionKey.startsWith("a11y:")) "自动采集：无障碍，时间为页面识别时间。" else ""))
                dao.markSource(transactionId, event.sourcePackage)
                dao.resolve(candidateId, "AUTO_POSTED", transactionId)
            }
        }
        true
    }
    override suspend fun confirm(id: String, draft: TransactionDraft): String = confirmWithAccount(id, draft, null)
    override suspend fun confirmWithAccount(id: String, draft: TransactionDraft, recognizedAccountName: String?): String = database.withTransaction {
        val candidate = dao.get(id) ?: throw IllegalArgumentException("事件不存在。")
        if (candidate.state in setOf("CONFIRMED", "AUTO_POSTED", "MERGED") && candidate.transactionId != null) return@withTransaction candidate.transactionId
        require(candidate.state == "PENDING") { "该事件已处理。" }
        require(!dao.isIgnored(candidate.sessionKey)) { "该事件已舍弃。" }
        require(candidate.reason != "UNSUPPORTED_CURRENCY") { "其他币种请通过原账单人工处理。" }
        dao.sessionTransaction(candidate.sessionKey)?.let { existing ->
            dao.resolvePendingSession(candidate.sessionKey, "CONFIRMED", existing)
            return@withTransaction existing
        }
        ledger.initialize()
        val accountId = draft.accountId ?: recognizedAccount(recognizedAccountName)
        val transactionId = ledger.save(draft.copy(accountId = accountId))
        dao.markSource(transactionId, candidate.sourcePackage)
        dao.resolvePendingSession(candidate.sessionKey, "CONFIRMED", transactionId)
        transactionId
    }
    override suspend fun ignore(id: String) = database.withTransaction {
        val candidate = dao.get(id) ?: throw IllegalArgumentException("事件不存在。")
        if (candidate.state != "PENDING") return@withTransaction
        dao.resolvePendingSession(candidate.sessionKey, "IGNORED", null)
    }
    override suspend fun merge(id: String, transactionId: String) = database.withTransaction {
        val candidate = dao.get(id) ?: throw IllegalArgumentException("事件不存在。")
        require(candidate.state == "PENDING" && candidate.relatedTransactionId == transactionId) { "无法关联该账单。" }
        requireNotNull(ledger.get(transactionId)) { "关联账单已删除。" }
        dao.resolve(id, "MERGED", transactionId)
    }
    override suspend fun clearResolved() = database.withTransaction { dao.hideResolved() }

    private suspend fun recognizedAccount(raw: String?): String? {
        val name = raw?.let(PaymentAccountRules::canonicalName) ?: return null
        val accounts = database.ledgerDao().allAccounts()
        val exact = accounts.filter { it.name.equals(name, ignoreCase = true) }
        val identity = PaymentAccountRules.bankIdentity(name)
        val matches = if (exact.isNotEmpty() || identity == null) exact else accounts.filter {
            PaymentAccountRules.bankIdentity(it.name) == identity &&
                (PaymentAccountRules.unspecifiedBankType(name) || PaymentAccountRules.unspecifiedBankType(it.name))
        }
        return when {
            matches.size == 1 && !matches.single().archived -> matches.single().id
            matches.isNotEmpty() -> null
            else -> ledger.saveAccount(name)
        }
    }

    private fun LatestCaptureRow.domain() = candidate.let {
        PendingCandidate(it.id, it.sourcePackage, it.versionCode, it.receivedAt, it.amountMinor, it.kind?.let(TransactionKind::valueOf),
            it.reason, it.state, it.transactionId, it.relatedTransactionId, merchantName, matchedAccountName,
            it.originalAmountMinor, it.discountMinor)
    }
}
