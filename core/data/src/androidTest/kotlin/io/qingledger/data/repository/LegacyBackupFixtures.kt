package io.qingledger.data.repository

import org.json.JSONArray
import org.json.JSONObject

/** Remove version-six fields when manufacturing a genuine older backup fixture. */
internal fun removeVersionSixCaptureColumns(payload: JSONObject) {
    val tables = payload.getJSONObject("tables")
    mapOf(
        "capture_configuration" to setOf("bookingMode", "dailySummaryEnabled"),
        "notification_candidates" to setOf("merchant", "accountName", "originalAmountMinor", "discountMinor"),
    ).forEach { (name, removed) ->
        val table = tables.getJSONObject(name)
        val columns = table.getJSONArray("columns")
        val keep = (0 until columns.length()).filter { columns.getString(it) !in removed }
        val rows = table.getJSONArray("rows")
        table.put("columns", JSONArray(keep.map { columns.getString(it) }))
        table.put("rows", JSONArray((0 until rows.length()).map { index -> JSONArray(keep.map { rows.getJSONArray(index).get(it) }) }))
    }
}
