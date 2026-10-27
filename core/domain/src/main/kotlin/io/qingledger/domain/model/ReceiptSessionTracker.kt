package io.qingledger.domain.model

data class ReceiptReference(val base: String, val party: String?, val session: String, val visual: String? = null)
data class PendingReceipt(val base: String, val party: String?, val session: String, val generation: Long, val at: Long)
data class ReceiptTrackingState(val recent: List<ReceiptReference> = emptyList(), val pending: PendingReceipt? = null)
data class ReceiptSessionDecision(val session: String, val revisit: Boolean)

class ReceiptSessionTracker(private val profile: String, private val newSession: () -> String) {
    var state = ReceiptTrackingState(); private set
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun base(source: String, kind: TransactionKind, amount: Long) = hash("$profile|$source|$kind|$amount")
    private fun party(merchant: String?) = merchant?.replace(Regex("\\s+"), "")?.lowercase()?.takeIf { it.isNotBlank() }?.let(::hash)
    private fun compatible(first: String?, second: String?) = first == null || second == null || first == second
    fun restore(saved: ReceiptTrackingState) { state = saved.copy(recent = saved.recent.takeLast(64)) }
    fun reconcile(sessions: Set<String>) { state = state.copy(recent = state.recent.filter { it.session in sessions }) }
    fun seed(source: String, kind: TransactionKind, amount: Long, merchant: String?, session: String) {
        val ref = ReceiptReference(base(source, kind, amount), party(merchant), session)
        if (state.recent.none { it.session == session || it.base == ref.base && it.party == ref.party }) state = state.copy(recent = (state.recent + ref).takeLast(64))
    }
    fun confirmation(source: String, amount: Long, merchant: String?, generation: Long, now: Long) {
        val key = base(source, TransactionKind.EXPENSE, amount); val who = party(merchant)
        val previous = state.pending?.takeIf { it.base == key && compatible(it.party, who) && it.generation == generation && now - it.at in 0..120000 }
        state = state.copy(pending = PendingReceipt(key, who ?: previous?.party, previous?.session ?: newSession(), generation, now))
    }
    fun clearPending() { state = state.copy(pending = null) }
    fun success(source: String, proposal: NotificationProposal, merchant: String?, generation: Long, now: Long, visual: String? = null): ReceiptSessionDecision {
        val key = base(source, requireNotNull(proposal.kind), requireNotNull(proposal.amountMinor)); val who = party(merchant)
        val pending = state.pending?.takeIf { it.base == key && compatible(it.party, who) && it.generation == generation && now - it.at in 0..120000 }
        val previous = if (pending == null) state.recent.lastOrNull { it.base == key && visual != null && it.visual == visual }
            ?: state.recent.lastOrNull { it.base == key && it.party == who }
            ?: state.recent.lastOrNull { it.base == key && compatible(it.party, who) } else null
        val session = pending?.session ?: previous?.session ?: newSession()
        val reference = ReceiptReference(key, who ?: pending?.party ?: previous?.party, session, visual ?: previous?.visual)
        state = ReceiptTrackingState((state.recent.filterNot { it.session == session } + reference).takeLast(64), null)
        return ReceiptSessionDecision(session, previous != null)
    }
}
