// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.serialization.Serializable

/**
 * A single typed value in a query result row or write operation.
 *
 * Scalars and arrays use the raw JSON shapes accepted by the Kit API.
 */
@Serializable(with = ValueSerializer::class)
sealed class Value {
    /** SQL NULL. */
    object Null : Value()

    /** Boolean (0 or 1 on the wire). */
    data class Bool(val value: Boolean) : Value()

    /** 64-bit signed integer. */
    data class Int64(val value: Long) : Value()

    /** 64-bit IEEE float. */
    data class Float64(val value: Double) : Value()

    /** UTF-8 text. */
    data class Text(val value: String) : Value()

    /** Dense embedding vector. */
    data class Embedding(val values: List<Double>) : Value()

    /** Sparse weighted-token vector. */
    data class Sparse(val terms: List<SparseTerm>) : Value()

    /** JSON array, including MinHash set members. */
    data class ArrayValue(val values: List<Value>) : Value()

    companion object {
        fun bool(b: Boolean) = Bool(b)
        fun int64(i: Long) = Int64(i)
        fun float64(f: Double) = Float64(f)
        fun text(s: String) = Text(s)
        fun embedding(values: List<Double>) = Embedding(values)
        fun sparse(terms: List<SparseTerm>) = Sparse(terms)
        fun array(values: List<Value>) = ArrayValue(values)
    }
}

data class SparseTerm(val token: Long, val weight: Double)

/** One cell in a result row: a column id paired with its value. */
data class Cell(
    val columnId: Long,
    val value: Value,
)

/** A result row: a list of cells. */
data class Row(
    val cells: List<Cell>,
) {
    /** Get the value of the first cell matching [columnId], or null. */
    fun get(columnId: Long): Value? = cells.firstOrNull { it.columnId == columnId }?.value

    /** Get the string value of the first cell matching [columnId], or null. */
    fun getString(columnId: Long): String? =
        (get(columnId) as? Value.Text)?.value

    /** Get the long value of the first cell matching [columnId], or null. */
    fun getLong(columnId: Long): Long? =
        (get(columnId) as? Value.Int64)?.value
}

/**
 * The result set returned by [MongrelDB.query].
 *
 * @param rows the matching rows.
 * @param truncated true if the result hit the query limit.
 */
data class Result(
    val rows: List<Row>,
    val truncated: Boolean = false,
)

/** Column definition for table creation. */
@Serializable
data class Column(
    val id: Long,
    val name: String,
    @kotlinx.serialization.SerialName("storage_type")
    val storageType: String,
    @kotlinx.serialization.SerialName("application_type")
    val applicationType: String = storageType,
    val nullable: Boolean = true,
    @kotlinx.serialization.SerialName("primary_key")
    val primaryKey: Boolean = false,
    val default: String? = null,
    val generated: Boolean = false,
    @kotlinx.serialization.SerialName("enum_variants")
    val enumVariants: List<String>? = null,
    @kotlinx.serialization.SerialName("default_value")
    val defaultValue: String? = null,
    @kotlinx.serialization.SerialName("default_value_json")
    val defaultValueJson: String? = null,
    @kotlinx.serialization.SerialName("default_expr")
    val defaultExpr: String? = null,
    /** Portable MongrelDB `EmbeddingSource` JSON for embedding columns. */
    val embeddingSourceJson: String? = null,
) {
    companion object {
        fun int64(name: String, id: Long, primaryKey: Boolean = false, nullable: Boolean = false) =
            Column(id, name, "int64", "int64", nullable, primaryKey)

        fun text(name: String, id: Long, nullable: Boolean = true) =
            Column(id, name, "text", "text", nullable)

        fun float64(name: String, id: Long, nullable: Boolean = true) =
            Column(id, name, "float64", "float64", nullable)

        fun bool(name: String, id: Long, nullable: Boolean = true) =
            Column(id, name, "bool", "bool", nullable)

        fun varchar(name: String, id: Long, nullable: Boolean = true) =
            Column(id, name, "varchar", "varchar", nullable)
    }
}

/** A single cell supplied to a write operation: column id + value. */
data class InputCell(
    val columnId: Long,
    val value: Value,
)

/** A condition for the native query builder. Each maps to a native engine index. */
sealed class Condition {
    /** Exact primary-key match by integer value. */
    data class PrimaryKeyInt(val value: Long) : Condition()

    /** Exact primary-key match by byte/string value. */
    data class PrimaryKeyString(val value: String) : Condition()

    /** Equality on a bitmap-indexed column (string value). */
    data class BitmapEq(val columnId: Long, val value: String) : Condition()

    data class BitmapIn(val columnId: Long, val values: List<Value>) : Condition()

    data class RangeInt(val columnId: Long, val lo: Long, val hi: Long) : Condition()

    /** Numeric range on a learned-range index. */
    data class Range(
        val columnId: Long,
        val lo: Double? = null,
        val hi: Double? = null,
        val loInclusive: Boolean = true,
        val hiInclusive: Boolean = true,
    ) : Condition()

    /** Full-text substring match (FM-index). */
    data class FmContains(val columnId: Long, val pattern: String) : Condition()

    data class FmContainsAll(val columnId: Long, val patterns: List<String>) : Condition()

    data class Ann(val columnId: Long, val query: List<Double>, val k: Int) : Condition()

    data class SparseMatch(val columnId: Long, val query: List<SparseTerm>, val k: Int) : Condition()

    data class MinHashSimilar(val columnId: Long, val query: List<Long>, val k: Int) : Condition()
    data class MinHashSimilarMembers(val columnId: Long, val members: List<Value>, val k: Int) : Condition()

    /** Null check — column is NULL. */
    data class IsNull(val columnId: Long) : Condition()

    /** Non-null check — column is NOT NULL. */
    data class IsNotNull(val columnId: Long) : Condition()
}

/** History retention configuration returned by `GET /history/retention`. */
@Serializable
data class HistoryRetention(
    @kotlinx.serialization.SerialName("history_retention_epochs")
    val historyRetentionEpochs: ULong,
    @kotlinx.serialization.SerialName("earliest_retained_epoch")
    val earliestRetainedEpoch: ULong,
)
