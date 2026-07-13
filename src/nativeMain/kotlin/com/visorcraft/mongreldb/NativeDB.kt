// SPDX-License-Identifier: MIT OR Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.visorcraft.mongreldb

import com.visorcraft.mongreldb.native.mongreldb_kit_database_t
import com.visorcraft.mongreldb.native.mongreldb_kit_open
import com.visorcraft.mongreldb.native.mongreldb_kit_create
import com.visorcraft.mongreldb.native.mongreldb_kit_database_free
import com.visorcraft.mongreldb.native.mongreldb_kit_sql_rows
import com.visorcraft.mongreldb.native.mongreldb_kit_sql_arrow
import com.visorcraft.mongreldb.native.mongreldb_kit_free_json
import com.visorcraft.mongreldb.native.mongreldb_kit_free_arrow
import com.visorcraft.mongreldb.native.mongreldb_kit_last_error
import com.visorcraft.mongreldb.native.mongreldb_kit_migrate_json
import com.visorcraft.mongreldb.native.mongreldb_kit_applied_migrations_json
import com.visorcraft.mongreldb.native.mongreldb_kit_query_select_json
import com.visorcraft.mongreldb.native.mongreldb_kit_query_insert_json
import com.visorcraft.mongreldb.native.mongreldb_kit_query_update_json
import com.visorcraft.mongreldb.native.mongreldb_kit_query_upsert_json
import com.visorcraft.mongreldb.native.mongreldb_kit_query_delete_json
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ULongVar

/**
 * Native embedded MongrelDB database (Tier 1).
 *
 * Links `libmongreldb_kit` + `libmongreldb` directly via the C ABI, running
 * the engine in-process with zero serialization overhead.
 */
class NativeDB private constructor(
    private val db: CPointer<mongreldb_kit_database_t>,
) : AutoCloseable {

    companion object {
        fun open(path: String): NativeDB {
            val handle = mongreldb_kit_open(path)
            checkNotNull(handle) { "mongreldb_kit_open failed: ${lastError()}" }
            return NativeDB(handle)
        }

        fun create(path: String, schema: String): NativeDB {
            val handle = mongreldb_kit_create(path, schema)
            checkNotNull(handle) { "mongreldb_kit_create failed: ${lastError()}" }
            return NativeDB(handle)
        }

        fun nativeAvailable(): Boolean = try {
            mongreldb_kit_last_error(); true
        } catch (e: Throwable) { false }

        fun lastError(): String =
            mongreldb_kit_last_error()?.toKString() ?: "(null)"
    }

    fun sqlRows(sql: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_sql_rows(db, sql, out.ptr)
        checkRc(rc, "sqlRows")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    fun sqlArrow(sql: String): ByteArray = memScoped {
        val outBuf = alloc<CPointerVar<UByteVar>>()
        val outLen = alloc<ULongVar>()
        val rc = mongreldb_kit_sql_arrow(db, sql, outBuf.ptr, outLen.ptr)
        checkRc(rc, "sqlArrow")
        val len = outLen.value.toInt()
        val result = if (len > 0) outBuf.value!!.readBytes(len) else ByteArray(0)
        mongreldb_kit_free_arrow(outBuf.value, outLen.value)
        result
    }

    fun migrate(migrationsJson: String) {
        val rc = mongreldb_kit_migrate_json(db, migrationsJson)
        checkRc(rc, "migrate")
    }

    fun appliedMigrations(): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_applied_migrations_json(db, out.ptr)
        checkRc(rc, "appliedMigrations")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    fun querySelect(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_select_json(db, queryJson, out.ptr)
        checkRc(rc, "querySelect")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    fun queryInsert(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_insert_json(db, queryJson, out.ptr)
        checkRc(rc, "queryInsert")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    fun queryUpdate(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_update_json(db, queryJson, out.ptr)
        checkRc(rc, "queryUpdate")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    fun queryUpsert(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_upsert_json(db, queryJson, out.ptr)
        checkRc(rc, "queryUpsert")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    fun queryDelete(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_delete_json(db, queryJson, out.ptr)
        checkRc(rc, "queryDelete")
        val result = out.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.value)
        result
    }

    override fun close() {
        mongreldb_kit_database_free(db)
    }

    private fun checkRc(rc: Int, op: String) {
        if (rc != 0) {
            throw QueryException("$op failed (rc=$rc): ${lastError()}")
        }
    }
}
