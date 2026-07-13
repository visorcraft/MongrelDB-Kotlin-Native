// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.serialization.Serializable

/**
 * A single typed value in a query result row or write operation.
 *
 * Mirrors the C [mongreldb_value] / C++ `mongreldb::Value` type: the
 * engine uses 5 value tags covering the full column-type space.
 */
@Serializable
sealed class Value {
    /** SQL NULL. */
    object Null : Value()

    /** Boolean (0 or 1 on the wire). */
    @Serializable(with = ValueSerializer::class)
    data class Bool(val value: Boolean) : Value()

    /** 64-bit signed integer. */
    @Serializable(with = ValueSerializer::class)
    data class Int64(val value: Long) : Value()

    /** 64-bit IEEE float. */
    @Serializable(with = ValueSerializer::class)
    data class Float64(val value: Double) : Value()

    /** UTF-8 text. */
    @Serializable(with = ValueSerializer::class)
    data class Text(val value: String) : Value()

    companion object {
        fun bool(b: Boolean) = Bool(b)
        fun int64(i: Long) = Int64(i)
        fun float64(f: Double) = Float64(f)
        fun text(s: String) = Text(s)
    }
}

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
