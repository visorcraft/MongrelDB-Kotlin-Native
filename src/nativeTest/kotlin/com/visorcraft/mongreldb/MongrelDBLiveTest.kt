// SPDX-License-Identifier: MIT OR Apache-2.0
package com.visorcraft.mongreldb

import kotlinx.serialization.json.Json
import kotlinx.cinterop.toKString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live integration tests against a running mongreldb-server daemon.
 *
 * Self-skip when no daemon is reachable — set `MONGRELDB_URL` to test,
 * or leave it unset to skip.
 */
class MongrelDBLiveTest {

    private val url = getenv("MONGRELDB_URL") ?: "http://127.0.0.1:8453"
    private var db: MongrelDB? = null
    private val json = Json { ignoreUnknownKeys = true }

    private fun skipIfNoDaemon(): MongrelDB {
        val client = MongrelDB(url)
        if (!client.health()) {
            client.close()
            // Skip: print and return a throw to halt the test.
            println("SKIP: no daemon at $url")
            throw TestSkippedException()
        }
        db = client
        return client
    }

    @AfterTest
    fun cleanup() {
        db?.close()
    }

    @Test
    fun testHealth() {
        val client = skipIfNoDaemon()
        assertTrue(client.health())
    }

    @Test
    fun testCreateTableAndCount() {
        val client = skipIfNoDaemon()
        val table = "kn_test_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.text("name", 2),
        ))
        try {
            assertEquals(0L, client.count(table))
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testPutAndCount() {
        val client = skipIfNoDaemon()
        val table = "kn_put_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.text("name", 2),
        ))
        try {
            client.put(table, listOf(
                InputCell(1, Value.Int64(1)),
                InputCell(2, Value.Text("alice")),
            ))
            assertEquals(1L, client.count(table))
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testUpsert() {
        val client = skipIfNoDaemon()
        val table = "kn_upsert_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.text("name", 2),
        ))
        try {
            client.put(table, listOf(
                InputCell(1, Value.Int64(1)),
                InputCell(2, Value.Text("alice")),
            ))
            // Upsert: same PK, different name.
            client.upsert(table, listOf(
                InputCell(1, Value.Int64(1)),
                InputCell(2, Value.Text("ALICE")),
            ), updateCells = listOf(
                InputCell(2, Value.Text("ALICE")),
            ))
            assertEquals(1L, client.count(table))
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testSql() {
        val client = skipIfNoDaemon()
        val table = "kn_sql_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.text("name", 2),
        ))
        try {
            client.put(table, listOf(
                InputCell(1, Value.Int64(1)),
                InputCell(2, Value.Text("alice")),
            ))
            val result = client.sql("SELECT id, name FROM $table ORDER BY id")
            assertTrue(result.contains("alice"))
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testQueryByPk() {
        val client = skipIfNoDaemon()
        val table = "kn_pk_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.text("name", 2),
        ))
        try {
            client.put(table, listOf(
                InputCell(1, Value.Int64(42)),
                InputCell(2, Value.Text("answer")),
            ))
            val result = client.query(
                table = table,
                conditions = listOf(Condition.PrimaryKeyInt(42)),
            )
            assertEquals(1, result.rows.size)
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testTableNames() {
        val client = skipIfNoDaemon()
        val table = "kn_names_${currentTimeMillis()}"
        client.createTable(table, listOf(Column.int64("id", 1, primaryKey = true)))
        try {
            val names = client.tableNames()
            assertTrue(names.contains(table))
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testDeleteByPk() {
        val client = skipIfNoDaemon()
        val table = "kn_del_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
        ))
        try {
            client.put(table, listOf(InputCell(1, Value.Int64(1))))
            assertEquals(1L, client.count(table))
            client.deleteByPk(table, Value.Int64(1))
            assertEquals(0L, client.count(table))
        } finally {
            client.dropTable(table)
        }
    }

    @Test
    fun testTransactionCommit() {
        val client = skipIfNoDaemon()
        val table = "kn_txn_${currentTimeMillis()}"
        client.createTable(table, listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.text("name", 2),
        ))
        try {
            client.commit(listOf(
                MongrelDB.Op(MongrelDB.Op.OpType.PUT, table, listOf(
                    InputCell(1, Value.Int64(1)),
                    InputCell(2, Value.Text("first")),
                )),
                MongrelDB.Op(MongrelDB.Op.OpType.PUT, table, listOf(
                    InputCell(1, Value.Int64(2)),
                    InputCell(2, Value.Text("second")),
                )),
            ))
            assertEquals(2L, client.count(table))
        } finally {
            client.dropTable(table)
        }
    }
}

/** Thrown to signal that a test was skipped (no daemon available). */
class TestSkippedException : RuntimeException("test skipped")

/** Minimal getenv for Kotlin/Native (platform.posix). */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun getenv(name: String): String? =
    platform.posix.getenv(name)?.toKString()

/** Milliseconds since the Unix epoch, for generating unique table names. */
private fun currentTimeMillis(): Long =
    kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
