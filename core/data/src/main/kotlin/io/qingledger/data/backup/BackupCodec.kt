package io.qingledger.data.backup

import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal val backupTables = listOf("books", "categories", "accounts", "transactions", "transaction_edits", "capture_configuration", "capture_sources", "notification_candidates")
internal data class BackupColumn(val name: String, val integer: Boolean, val nullable: Boolean)
internal data class BackupTable(val columns: List<BackupColumn>, val rows: List<List<Any?>>)
internal data class BackupSnapshot(val createdAt: Long, val preferences: BackupPreferences, val tables: Map<String, BackupTable>) {
    fun info(): BackupInfo {
        val transactions = tables.getValue("transactions")
        val deleted = transactions.columns.indexOfFirst { it.name == "deletedAt" }
        return BackupInfo(createdAt, transactions.rows.count { it[deleted] == null }, tables.getValue("accounts").rows.size, tables.getValue("categories").rows.size)
    }
}

internal object BackupCodec {
    fun encode(snapshot: BackupSnapshot): ByteArray {
        val payload = JSONObject().put("theme", snapshot.preferences.theme.name).put("method", snapshot.preferences.method.name)
        val tables = JSONObject()
        snapshot.tables.forEach { (name, table) ->
            tables.put(name, JSONObject().put("columns", JSONArray(table.columns.map { it.name })).put("rows", JSONArray().apply {
                table.rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it ?: JSONObject.NULL) } }) }
            }))
        }
        val json = payload.put("tables", tables).toString()
        return JSONObject().put("format", "qingledger-backup").put("version", 1).put("databaseVersion", 4)
            .put("createdAt", snapshot.createdAt).put("sha256", digest(json)).put("payload", json).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= BackupRepository.MAX_BYTES) { "备份超过 16MB，当前版本暂不支持。" } }
    }

    fun decode(bytes: ByteArray, expected: Map<String, List<BackupColumn>>): BackupSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= BackupRepository.MAX_BYTES) { "备份文件为空或超过 16MB。" }
        try {
            val envelope = parse(String(bytes, Charsets.UTF_8))
            val legacy = exactInteger(envelope.get("databaseVersion"), 3)
            require(envelope.optString("format") == "qingledger-backup" && exactInteger(envelope.get("version"), 1) && (legacy || exactInteger(envelope.get("databaseVersion"), 4))) { "备份格式或数据库版本不支持。" }
            val payload = envelope.get("payload") as? String ?: error("Invalid payload")
            require(envelope.getString("sha256") == digest(payload)) { "备份校验失败，文件可能已损坏。" }
            val root = parse(payload)
            val prefs = BackupPreferences(ThemeMode.valueOf(root.getString("theme")), CaptureMethod.valueOf(root.getString("method")))
            val input = root.getJSONObject("tables")
            require(input.keys().asSequence().toSet() == backupTables.toSet()) { "备份缺少数据表。" }
            var count = 0
            val tables = backupTables.associateWith { name ->
                val table = input.getJSONObject(name)
                val columns = expected.getValue(name)
                val names = table.getJSONArray("columns")
                val inputColumns = if (legacy && name == "transactions") columns.filter { it.name != "originalExpenseId" } else columns
                require((0 until names.length()).map { names.getString(it) } == inputColumns.map { it.name }) { "备份字段不匹配。" }
                val rows = table.getJSONArray("rows")
                count += rows.length()
                require(count <= 200000) { "备份记录过多，当前版本暂不支持。" }
                BackupTable(columns, (0 until rows.length()).map { index ->
                    val row = rows.getJSONArray(index)
                    require(row.length() == inputColumns.size) { "备份记录字段不完整。" }
                    val values = inputColumns.mapIndexed { position, column ->
                        val value = row.get(position)
                        when {
                            value == JSONObject.NULL -> { require(column.nullable) { "备份必要字段为空。" }; null }
                            column.integer -> { require(value is Int || value is Long) { "备份整数格式无效。" }; (value as Number).toLong() }
                            else -> { require(value is String && value.length <= 65536) { "备份文字格式无效。" }; value }
                        }
                    }
                    if (inputColumns.size != columns.size) values + null else values
                })
            }
            val createdAt = envelope.get("createdAt")
            require(createdAt is Long || createdAt is Int)
            return BackupSnapshot((createdAt as Number).toLong(), prefs, tables)
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("不是有效的轻账备份文件。") }
        catch (_: StackOverflowError) { throw IllegalArgumentException("备份结构无效。") }
    }

    private fun parse(text: String): JSONObject {
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { char ->
            if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
            else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 32) { "备份结构过深。" } }; '}', ']' -> { depth--; require(depth >= 0) } }
        }
        require(!quoted && depth == 0) { "备份结构不完整。" }
        return JSONObject(text)
    }
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun exactInteger(value: Any, expected: Long): Boolean = (value is Int || value is Long) && (value as Number).toLong() == expected
}
