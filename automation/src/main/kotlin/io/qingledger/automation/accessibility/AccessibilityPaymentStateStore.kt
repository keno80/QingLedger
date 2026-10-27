package io.qingledger.automation.accessibility

import android.content.Context
import io.qingledger.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Private structured state only; no image or page text. Completed references contain hashes. */
internal class AccessibilityPaymentStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("accessibility_payment_sessions", Context.MODE_PRIVATE)
    fun restore(tracker: ReceiptSessionTracker, cache: OcrPaymentContextCache, now: Long) {
        runCatching {
            val text = preferences.getString("state", null) ?: return
            require(text.length <= 65536)
            val root = JSONObject(text); require(root.getInt("version") == 1)
            fun reference(item: JSONObject): ReceiptReference {
                val base = item.getString("base"); val party = item.optionalString("party"); val session = item.getString("session")
                require(Regex("[a-f0-9]{64}").matches(base) && (party == null || Regex("[a-f0-9]{64}").matches(party)))
                require(Regex("a11y:[a-f0-9]{64}").matches(session))
                val visual = item.optionalString("visual")?.takeIf { Regex("[a-f0-9]{64}").matches(it) }
                return ReceiptReference(base, party, session, visual)
            }
            val array = root.optJSONArray("recent") ?: JSONArray()
            val recent = (0 until minOf(64, array.length())).mapNotNull { runCatching { reference(array.getJSONObject(it)) }.getOrNull() }
            val pending = root.optJSONObject("pending")?.let { item -> reference(item).let { PendingReceipt(it.base, it.party, it.session, item.getLong("generation"), item.getLong("at")) } }
                ?.takeIf { now - it.at in 0..120000 }
            tracker.restore(ReceiptTrackingState(recent, pending))
            val cached = root.optJSONObject("context")?.let { item -> OcrPaymentCacheState(item.getString("source"), item.getLong("generation"),
                OcrPaymentContext(item.getLong("amount"), item.optionalString("merchant"), item.optionalString("account"), item.optionalLong("original"), item.optionalLong("discount")), item.getLong("at")) }
            cache.restore(cached, now)
        }.onFailure { tracker.restore(ReceiptTrackingState()); cache.clear() }
    }
    fun save(tracker: ReceiptSessionTracker, cache: OcrPaymentContextCache) {
        fun reference(base: String, party: String?, session: String, visual: String? = null) = JSONObject().put("base", base).put("party", party ?: JSONObject.NULL).put("session", session).put("visual", visual ?: JSONObject.NULL)
        val root = JSONObject().put("version", 1).put("recent", JSONArray().apply {
            tracker.state.recent.takeLast(64).forEach { put(reference(it.base, it.party, it.session, it.visual)) }
        })
        tracker.state.pending?.let { root.put("pending", reference(it.base, it.party, it.session).put("generation", it.generation).put("at", it.at)) }
        cache.snapshot()?.let { item -> root.put("context", JSONObject().put("source", item.source).put("generation", item.generation).put("at", item.at)
            .put("amount", item.context.amountMinor).put("merchant", item.context.merchant ?: JSONObject.NULL).put("account", item.context.accountName ?: JSONObject.NULL)
            .put("original", item.context.originalAmountMinor ?: JSONObject.NULL).put("discount", item.context.discountMinor ?: JSONObject.NULL)) }
        // The session must reach disk before its financial event is queued.
        check(preferences.edit().putString("state", root.toString()).commit()) { "Cannot persist receipt identity" }
    }
    private fun JSONObject.optionalString(name: String) = if (isNull(name)) null else getString(name)
    private fun JSONObject.optionalLong(name: String) = if (isNull(name)) null else getLong(name)
    companion object {
        fun newSession() = "a11y:" + MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
