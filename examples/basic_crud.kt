// SPDX-License-Identifier: MIT OR Apache-2.0
/*
 * Example: basic CRUD operations with the MongrelDB Kotlin/Native HTTP client.
 *
 * Build (from the repo root, as an executable program in a separate Gradle
 * module that consumes this client via includeBuild):
 *
 *   # In a demo module's build.gradle.kts:
 *   #   kotlin { linuxX64() { binaries.executable { entryPoint = "main" } } }
 *   #   includeBuild("../MongrelDB-Kotlin-Native")
 *
 *   ./gradlew linkReleaseExecutableLinuxX64
 *   ./build/bin/native/releaseExecutable/demo.kexe
 *
 * Or run via the Gradle application entry point of your KMP project.
 *
 * Requires a mongreldb-server daemon running on http://127.0.0.1:8453, or
 * set MONGRELDB_URL to point at a running daemon.
 *
 * Creates a table, inserts three rows, counts them, queries all rows, upserts
 * (updates) one row by primary key, deletes one row, then drops the table.
 * Progress is printed at every step.
 *
 * The "status" column is an enum ("active" | "inactive" | "paused") with a
 * default of "active"; the "score" column has a numeric default of "0.0".
 * These are emitted as "enum_variants" and "default_value_json" keys in the
 * /kit/create_table wire JSON.
 */

@file:kotlin.OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.visorcraft.mongreldb.examples

import com.visorcraft.mongreldb.*

private const val DB_URL_DEFAULT = "http://127.0.0.1:8453"
private const val TABLE_PREFIX = "example_crud_"

/**
 * Minimal getenv for Kotlin/Native (reads `platform.posix.getenv`).
 * Returns null when the variable is unset (POSIX `getenv` returns NULL).
 */
private fun processEnv(name: String): String? =
    platform.posix.getenv(name)?.let { kotlinx.cinterop.toKString(it) }

/**
 * Column schema shared with the other examples:
 *   col 1 = id (int64, primary key)
 *   col 2 = name (varchar)
 *   col 3 = score (float64, default "0.0")
 *   col 4 = status (varchar, enum ["active","inactive","paused"], default "active")
 */
private val columns = listOf(
    Column.int64("id", 1, primaryKey = true),
    Column.varchar("name", 2, nullable = false),
    Column(
        id = 3, name = "score",
        storageType = "float64", nullable = false,
        defaultValueJson = "0.0",
    ),
    Column(
        id = 4, name = "status",
        storageType = "varchar", nullable = false,
        enumVariants = listOf("active", "inactive", "paused"),
        defaultValueJson = "\"active\"",
    ),
)

/** Build a four-cell input row from plain Kotlin values. */
private fun row(id: Long, name: String, score: Double, status: String): List<InputCell> =
    listOf(
        InputCell(1, Value.int64(id)),
        InputCell(2, Value.text(name)),
        InputCell(3, Value.float64(score)),
        InputCell(4, Value.text(status)),
    )

/** Print every cell of a query result, decoding the Value subtype. */
private fun printResult(result: Result) {
    for (row in result.rows) {
        print("  { ")
        for ((j, cell) in row.cells.withIndex()) {
            print("col${cell.columnId}=")
            when (val v = cell.value) {
                is Value.Int64   -> print(v.value)
                is Value.Float64 -> print(v.value)
                is Value.Text    -> print(v.value)
                is Value.Bool    -> print(if (v.value) "true" else "false")
                is Value.Null    -> print("null")
            }
            if (j + 1 < row.cells.size) print(", ")
        }
        println(" }")
    }
}

fun main() {
    // Per-run unique suffix (epoch millis) keeps every invocation isolated on a
    // shared daemon.
    val table = TABLE_PREFIX + platform.posix.time(null)

    val url = processEnv("MONGRELDB_URL")?.takeIf { it.isNotEmpty() } ?: DB_URL_DEFAULT

    // `use {}` guarantees close() on the HTTP client even on early return or
    // exception (MongrelDB implements AutoCloseable).
    MongrelDB(url).use { db ->
        var tableCreated = false

        try {
            // 1. Health check; bail out if the daemon is unreachable.
            if (!db.health()) {
                System.err.println("daemon not reachable at $url")
                kotlin.system.exitProcess(1)
            }
            println("Connected to MongrelDB")

            // 2. Create the table.
            val tid = db.createTable(table, columns)
            tableCreated = true
            println("Created table $table (id $tid)")

            // 3. Insert three rows. Each status is one of the allowed enum variants.
            db.put(table, row(1, "Alice", 95.5, "active"))
            db.put(table, row(2, "Bob", 82.0, "inactive"))
            db.put(table, row(3, "Carol", 78.3, "paused"))
            println("Inserted 3 rows")

            // 4. Count.
            var n = db.count(table)
            println("Total rows: $n")

            // 5. Query all rows (no conditions, no projection, no limit).
            val all = db.query(table = table)
            println("Query returned ${all.rows.size} rows:")
            printResult(all)

            // 6. Upsert (update) Alice's score and mark her paused. updateCells
            //    supplies the values written on a primary-key conflict.
            db.upsert(
                table = table,
                cells = row(1, "Alice", 100.0, "paused"),
                updateCells = listOf(
                    InputCell(2, Value.text("Alice")),
                    InputCell(3, Value.float64(100.0)),
                    InputCell(4, Value.text("paused")),
                ),
            )
            println("Upserted Alice's score to 100.0")
            n = db.count(table)
            println("Total rows after upsert: $n")

            // 7. Delete Carol (primary key 3).
            db.deleteByPk(table, Value.int64(3))
            n = db.count(table)
            println("Deleted Carol; remaining rows: $n")

            // All steps completed.
        } finally {
            // Guaranteed cleanup: drop the table if it was created.
            if (tableCreated) {
                try {
                    db.dropTable(table)
                    println("Dropped table $table")
                } catch (e: MongrelDBException) {
                    System.err.println("drop_table failed: ${e.message}")
                }
            }
        }
    }
}
