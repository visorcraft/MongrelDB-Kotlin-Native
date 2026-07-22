// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * The MongrelDB HTTP client for Kotlin/Native.
 *
 * Talks to a running [mongreldb-server](https://github.com/visorcraft/MongrelDB)
 * daemon over HTTP using the standard JSON API. Mirrors the surface of the C,
 * C++, Java, and Kotlin/JVM clients: typed CRUD, query builder, batch
 * transactions with idempotency keys, full SQL access, schema introspection,
 * and history-retention management.
 *
 * **Thread safety:** a client is NOT thread-safe. Create one per coroutine
 * or serialize access externally.
 *
 * **Authentication:** use [MongrelDB] for no auth, or the [withToken] /
 * [withBasicAuth] factories for bearer-token or HTTP Basic modes.
 *
 * @param url the daemon URL (default `http://127.0.0.1:8453`).
 */
class MongrelDB(
    url: String = DEFAULT_URL,
    authHeader: Pair<String, String>? = null,
) : AutoCloseable {

    private val transport = HttpTransport(url, authHeader)

    private val json = transport.json

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun close() = transport.close()

    // ── Health & tables ────────────────────────────────────────────────────

    /** Check if the daemon is reachable and healthy. */
    fun health(): Boolean = runBlocking { transport.getOk("/health") }

    /** List all table names. */
    fun tableNames(): List<String> = runBlocking {
        val body = transport.getBody("/tables")
        val arr = json.parseToJsonElement(body).jsonArray
        arr.map { it.jsonPrimitive.content }
    }

    /** Create a table. Returns the assigned table id, or null if the server omitted it. */
    fun createTable(name: String, columns: List<Column>): Long? = runBlocking {
        createTableInternal(name, columns, null, null)
    }

    /** Create a table with full Kit constraints JSON (unique, FK, CHECK). */
    fun createTable(name: String, columns: List<Column>, constraintsJson: String): Long? =
        runBlocking { createTableInternal(name, columns, constraintsJson, null) }

    /** Create a table with full secondary-index definitions. */
    fun createTable(
        name: String,
        columns: List<Column>,
        constraintsJson: String?,
        indexesJson: String,
    ): Long? = runBlocking { createTableInternal(name, columns, constraintsJson, indexesJson) }

    private suspend fun createTableInternal(
        name: String,
        columns: List<Column>,
        constraintsJson: String?,
        indexesJson: String?,
    ): Long? {
        val body = buildJsonObject {
            put("name", name)
            put("columns", serializeColumns(columns))
            if (constraintsJson != null) {
                put("constraints", json.parseToJsonElement(constraintsJson))
            }
            if (indexesJson != null) {
                put("indexes", json.parseToJsonElement(indexesJson))
            }
        }.toString()
        val resp = transport.postBody("/kit/create_table", body)
        val obj = json.parseToJsonElement(resp).let { it as? JsonObject }
        return obj?.get("table_id")?.jsonPrimitive?.longOrNull
    }

    /** Drop a table by name. */
    fun dropTable(name: String) = runBlocking {
        transport.deleteBody("/tables/${urlEncode(name)}")
    }

    /** Return the row count for a table. */
    fun count(table: String): Long = runBlocking {
        val body = transport.getBody("/tables/${urlEncode(table)}/count")
        val obj = json.parseToJsonElement(body).let { it as? JsonObject }
        obj?.get("count")?.jsonPrimitive?.long ?: 0L
    }

    // ── CRUD ───────────────────────────────────────────────────────────────

    /** Insert a row. */
    fun put(table: String, cells: List<InputCell>, idempotencyKey: String? = null) =
        runBlocking {
            val op = serializeOp("put", table, cells, null, idempotencyKey)
            transport.postBody("/kit/txn", serializeTxn(listOf(op)))
        }

    /** Insert a row, or update it on a primary-key conflict. */
    fun upsert(
        table: String,
        cells: List<InputCell>,
        updateCells: List<InputCell> = emptyList(),
        idempotencyKey: String? = null,
    ) = runBlocking {
        val op = serializeOp("upsert", table, cells, updateCells, idempotencyKey)
        transport.postBody("/kit/txn", serializeTxn(listOf(op)))
    }

    /** Delete a row by its internal row id. */
    fun delete(table: String, rowId: Long) = runBlocking {
        val op = buildJsonObject {
            put("type", "delete")
            put("table", table)
            put("row_id", rowId)
        }
        transport.postBody("/kit/txn", serializeTxn(listOf(op)))
    }

    /** Delete a row by its primary-key value. */
    fun deleteByPk(table: String, pk: Value) = runBlocking {
        val op = buildJsonObject {
            put("type", "delete_by_pk")
            put("table", table)
            put("pk_value", valueToJson(pk))
        }
        transport.postBody("/kit/txn", serializeTxn(listOf(op)))
    }

    // ── Batch transactions ─────────────────────────────────────────────────

    /** A staged operation in a batch transaction. */
    data class Op(
        val type: OpType,
        val table: String,
        val cells: List<InputCell> = emptyList(),
        val updateCells: List<InputCell> = emptyList(),
        val rowId: Long = 0,
        val pkValue: Value = Value.Null,
    ) {
        enum class OpType { PUT, UPSERT, DELETE, DELETE_BY_PK }
    }

    /**
     * Commit a batch of operations atomically. The engine enforces unique,
     * foreign-key, and check constraints at commit time; any violation rolls
     * back the entire batch.
     */
    fun commit(ops: List<Op>, idempotencyKey: String? = null) = runBlocking {
        transport.postBody("/kit/txn", serializeTxn(ops.map { serializeOp(it) }))
    }

    // ── Query builder ──────────────────────────────────────────────────────

    /**
     * Run a native query against [table]. [conditions] (AND-ed) push down to
     * the engine's specialized indexes for sub-millisecond lookups. [projection]
     * restricts the returned column ids. [limit] caps the result count.
     */
    fun query(
        table: String,
        conditions: List<Condition> = emptyList(),
        projection: List<Long> = emptyList(),
        limit: Long = 0,
    ): Result = runBlocking {
        val body = buildJsonObject {
            put("table", table)
            if (conditions.isNotEmpty()) {
                put("conditions", buildJsonArray {
                    conditions.forEach { add(serializeCondition(it)) }
                })
            }
            if (projection.isNotEmpty()) {
                put("projection", buildJsonArray {
                    projection.forEach { add(it) }
                })
            }
            if (limit > 0) put("limit", limit)
        }.toString()
        val resp = transport.postBody("/kit/query", body)
        parseResult(resp)
    }

    // ── SQL ────────────────────────────────────────────────────────────────

    /**
     * Text → embed → ANN retrieve (POST /kit/retrieve_text, 0.64+).
     * Returns the raw JSON response body.
     */
    fun retrieveText(
        table: String,
        embeddingColumn: Int,
        text: String,
        k: Int? = null,
        deadlineMs: Long? = null,
        maxWork: Long? = null,
    ): String = runBlocking {
        require(table.isNotEmpty()) { "table is required" }
        require(text.isNotEmpty()) { "text is required" }
        val body =
            buildJsonObject {
                put("table", table)
                put("embedding_column", embeddingColumn)
                put("text", text)
                if (k != null) put("k", k)
                if (deadlineMs != null) put("deadline_ms", deadlineMs)
                if (maxWork != null) put("max_work", maxWork)
            }.toString()
        transport.postBody("/kit/retrieve_text", body)
    }

    /**
     * Retained SQL status for durable recovery (GET /queries/{query_id}).
     * Returns the raw JSON response body.
     */
    fun queryStatus(queryId: String): String = runBlocking {
        require(queryId.isNotEmpty()) { "query_id is required" }
        transport.getBody("/queries/${urlEncode(queryId)}")
    }

    /**
     * Request cancellation of a running SQL query
     * (POST /queries/{query_id}/cancel).
     */
    fun cancelQuery(queryId: String): String = runBlocking {
        require(queryId.isNotEmpty()) { "query_id is required" }
        transport.postBody("/queries/${urlEncode(queryId)}/cancel", "{}")
    }

    /**
     * Execute a SQL statement via the /sql endpoint. Returns the raw response
     * body (JSON for SELECT, status text for DDL/DML).
     *
     * **WARNING:** the SQL string is sent as-is to the server. Never
     * interpolate untrusted user input.
     */
    fun sql(statement: String): String = runBlocking {
        val body = buildJsonObject {
            put("format", "json")
            put("sql", statement)
        }.toString()
        transport.postBody("/sql", body)
    }

    // ── Schema ─────────────────────────────────────────────────────────────

    /** Return the full schema catalog as raw JSON. */
    fun schema(): String = runBlocking { transport.getBody("/kit/schema") }

    /** Return the descriptor for a single table as raw JSON. */
    fun schemaFor(table: String): String =
        runBlocking { transport.getBody("/kit/schema/${urlEncode(table)}") }

    // ── History retention ──────────────────────────────────────────────────

    /** Get the history-retention configuration. */
    fun historyRetention(): HistoryRetention = runBlocking {
        val body = transport.getBody("/history/retention")
        json.decodeFromString(HistoryRetention.serializer(), body)
    }

    /** Set the history-retention epochs. */
    fun setHistoryRetentionEpochs(epochs: ULong): HistoryRetention = runBlocking {
        val body = buildJsonObject {
            put("history_retention_epochs", epochs.toLong())
        }.toString()
        transport.putBody("/history/retention", body)
        historyRetention()
    }

    // ── Serialization helpers ──────────────────────────────────────────────

    private fun serializeColumns(columns: List<Column>): JsonArray = buildJsonArray {
        for (col in columns) {
            add(buildJsonObject {
                put("id", col.id)
                put("name", col.name)
                put("ty", col.storageType)
                put("nullable", col.nullable)
                put("primary_key", col.primaryKey)
                put("default", col.default)
                put("generated", col.generated)
                if (col.enumVariants != null) {
                    put("enum_variants", buildJsonArray {
                        col.enumVariants.forEach { add(it) }
                    })
                }
                if (col.defaultValue != null) put("default_value", col.defaultValue)
                if (col.defaultValueJson != null) put("default_value_json", col.defaultValueJson)
                if (col.defaultExpr != null) put("default_expr", col.defaultExpr)
                if (col.embeddingSourceJson != null) {
                    put("embedding_source", json.parseToJsonElement(col.embeddingSourceJson))
                }
            })
        }
    }

    private fun serializeOp(
        type: String,
        table: String,
        cells: List<InputCell>?,
        updateCells: List<InputCell>?,
        idempotencyKey: String?,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("table", table)
        if (cells != null) {
            put("cells", buildJsonArray {
                cells.forEach { cell ->
                    add(cell.columnId)
                    add(valueToJson(cell.value))
                }
            })
        }
        if (updateCells != null && updateCells.isNotEmpty()) {
            put("update_cells", buildJsonArray {
                updateCells.forEach { cell ->
                    add(cell.columnId)
                    add(valueToJson(cell.value))
                }
            })
        }
        if (idempotencyKey != null) put("idempotency_key", idempotencyKey)
    }

    private fun serializeOp(op: Op): JsonObject = when (op.type) {
        Op.OpType.PUT -> serializeOp("put", op.table, op.cells, null, null)
        Op.OpType.UPSERT -> serializeOp("upsert", op.table, op.cells, op.updateCells, null)
        Op.OpType.DELETE -> buildJsonObject {
            put("type", "delete")
            put("table", op.table)
            put("row_id", op.rowId)
        }
        Op.OpType.DELETE_BY_PK -> buildJsonObject {
            put("type", "delete_by_pk")
            put("table", op.table)
            put("pk_value", valueToJson(op.pkValue))
        }
    }

    private fun serializeTxn(ops: List<JsonObject>): String = buildJsonObject {
        put("ops", buildJsonArray { ops.forEach { add(it) } })
    }.toString()

    private fun serializeCondition(cond: Condition): JsonObject = when (cond) {
        is Condition.PrimaryKeyInt -> buildJsonObject {
            put("pk", buildJsonObject { put("value", cond.value) })
        }
        is Condition.PrimaryKeyString -> buildJsonObject {
            put("pk", buildJsonObject { put("value", cond.value) })
        }
        is Condition.BitmapEq -> buildJsonObject {
            put("bitmap_eq", buildJsonObject {
                put("column_id", cond.columnId)
                put("value", cond.value)
            })
        }
        is Condition.BitmapIn -> buildJsonObject {
            put("bitmap_in", buildJsonObject {
                put("column_id", cond.columnId)
                put("values", buildJsonArray { cond.values.forEach { add(valueToJson(it)) } })
            })
        }
        is Condition.RangeInt -> buildJsonObject {
            put("range", buildJsonObject {
                put("column_id", cond.columnId)
                put("lo", cond.lo)
                put("hi", cond.hi)
            })
        }
        is Condition.Range -> buildJsonObject {
            require(cond.lo != null && cond.hi != null) { "Range requires lo and hi" }
            put("range_f64", buildJsonObject {
                put("column_id", cond.columnId)
                put("lo", cond.lo)
                put("lo_inclusive", cond.loInclusive)
                put("hi", cond.hi)
                put("hi_inclusive", cond.hiInclusive)
            })
        }
        is Condition.FmContains -> buildJsonObject {
            put("fm_contains", buildJsonObject {
                put("column_id", cond.columnId)
                put("pattern", cond.pattern)
            })
        }
        is Condition.FmContainsAll -> buildJsonObject {
            put("fm_contains_all", buildJsonObject {
                put("column_id", cond.columnId)
                put("patterns", buildJsonArray { cond.patterns.forEach { add(it) } })
            })
        }
        is Condition.Ann -> buildJsonObject {
            put("ann", buildJsonObject {
                put("column_id", cond.columnId)
                put("query", buildJsonArray { cond.query.forEach { add(it) } })
                put("k", cond.k)
            })
        }
        is Condition.SparseMatch -> buildJsonObject {
            put("sparse_match", buildJsonObject {
                put("column_id", cond.columnId)
                put("query", buildJsonArray {
                    cond.query.forEach { term ->
                        add(buildJsonArray { add(term.token); add(term.weight) })
                    }
                })
                put("k", cond.k)
            })
        }
        is Condition.MinHashSimilar -> buildJsonObject {
            put("minhash_similar", buildJsonObject {
                put("column_id", cond.columnId)
                put("query", buildJsonArray { cond.query.forEach { add(it) } })
                put("k", cond.k)
            })
        }
        is Condition.MinHashSimilarMembers -> buildJsonObject {
            put("minhash_similar_members", buildJsonObject {
                put("column_id", cond.columnId)
                put("members", buildJsonArray { cond.members.forEach { add(valueToJson(it)) } })
                put("k", cond.k)
            })
        }
        is Condition.IsNull -> buildJsonObject {
            put("is_null", buildJsonObject { put("column_id", cond.columnId) })
        }
        is Condition.IsNotNull -> buildJsonObject {
            put("is_not_null", buildJsonObject { put("column_id", cond.columnId) })
        }
    }

    private fun parseResult(body: String): Result {
        val obj = json.parseToJsonElement(body).let { it as? JsonObject }
            ?: throw QueryException("unexpected query response: not a JSON object")
        val rowsArr = obj["rows"]?.jsonArray ?: JsonArray(emptyList())
        val truncated = obj["truncated"]?.jsonPrimitive?.let { it.content == "true" || it.content == "1" } ?: false

        val rows = rowsArr.map { rowEl ->
            val rowObj = rowEl.jsonObject
            val flat = rowObj["cells"]?.jsonArray ?: JsonArray(emptyList())
            val cellsList = flat.chunked(2).mapNotNull { pair ->
                if (pair.size != 2) null else Cell(pair[0].jsonPrimitive.long, jsonToValue(pair[1]))
            }
            Row(cellsList)
        }
        return Result(rows, truncated)
    }

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8453"

        /** Create a client with a Bearer token (--auth-token mode). */
        fun withToken(token: String, url: String = DEFAULT_URL): MongrelDB =
            MongrelDB(url, "Authorization" to "Bearer $token")

        /** Create a client with HTTP Basic credentials (--auth-users mode). */
        fun withBasicAuth(
            username: String,
            password: String,
            url: String = DEFAULT_URL,
        ): MongrelDB =
            MongrelDB(url, "Authorization" to "Basic ${basicAuth(username, password)}")
    }
}

/** Compute the HTTP Basic auth header value. */
private fun basicAuth(user: String, pass: String): String {
    // Kotlin/Native doesn't have java.util.Base64, so we use a minimal encoder.
    val raw = "$user:$pass".encodeToByteArray()
    return base64Encode(raw)
}

/** Minimal Base64 encoder (no dependency). */
private fun base64Encode(data: ByteArray): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
        sb.append(table[b0 ushr 2])
        sb.append(table[((b0 and 0x03) shl 4) or (if (b1 >= 0) (b1 ushr 4) else 0)])
        sb.append(if (b1 >= 0) table[((b1 and 0x0F) shl 2) or (if (b2 >= 0) (b2 ushr 6) else 0)] else '=')
        sb.append(if (b2 >= 0) table[b2 and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

/** Minimal URL-encoder for path segments. */
private fun urlEncode(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        when {
            ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' ->
                sb.append(ch)
            else -> {
                val bytes = ch.toString().encodeToByteArray()
                for (b in bytes) {
                    sb.append('%')
                    sb.append("0123456789ABCDEF"[(b.toInt() ushr 4) and 0xF])
                    sb.append("0123456789ABCDEF"[b.toInt() and 0xF])
                }
            }
        }
    }
    return sb.toString()
}
