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
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.value

/**
 * Native embedded MongrelDB database (Tier 1).
 *
 * Links `libmongreldb_kit` + `libmongreldb` directly via the C ABI, running
 * the engine in-process with zero serialization overhead. No daemon, no HTTP.
 * Mirrors the C/C++ `native_basic_crud` examples and the Java/Kotlin-JVM
 * `NativeDB` class.
 *
 * **Thread safety:** uses `Rc<RefCell>` internally (single-threaded). Create
 * one [NativeDB] per worker. Do not share across threads.
 *
 * @param db the opaque FFI handle. Obtain via [open] or [create].
 */
class NativeDB private constructor(
    private val db: CPointer<mongreldb_kit_database_t>,
) : AutoCloseable {

    companion object {
        /** Open an existing Kit database at [path]. */
        fun open(path: String): NativeDB {
            val handle = memScoped {
                mongreldb_kit_open(path.cstr.ptr)
            }
            checkNotNull(handle) { "mongreldb_kit_open failed: ${lastError()}" }
            return NativeDB(handle)
        }

        /** Create a fresh Kit database at [path] with a JSON [schema]. */
        fun create(path: String, schema: String): NativeDB {
            val handle = memScoped {
                mongreldb_kit_create(path.cstr.ptr, schema.cstr.ptr)
            }
            checkNotNull(handle) { "mongreldb_kit_create failed: ${lastError()}" }
            return NativeDB(handle)
        }

        /** Check whether the native library is loadable. */
        fun nativeAvailable(): Boolean = try {
            mongreldb_kit_last_error()
            true
        } catch (e: Throwable) {
            false
        }

        /** Get the last FFI error message. */
        fun lastError(): String {
            val ptr = mongreldb_kit_last_error()
            return ptr?.toKString() ?: "(null)"
        }
    }

    /**
     * Run SQL and return the result as a JSON array of row objects.
     * For DDL/DML the result is `[]`.
     */
    fun sqlRows(sql: String): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_sql_rows(db, sql.cstr.ptr, outJson.ptr)
        checkRc(rc, "sqlRows")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
        result
    }

    /**
     * Run SQL and return the result as Arrow IPC file bytes.
     * For DDL/DML the result is an empty byte array.
     */
    fun sqlArrow(sql: String): ByteArray = memScoped {
        val outBuf = alloc<CPointerVar<ByteVar>>()
        val outLen = alloc<ULongVar>()
        val rc = mongreldb_kit_sql_arrow(db, sql.cstr.ptr, outBuf.ptr, outLen.ptr)
        checkRc(rc, "sqlArrow")
        val len = outLen.value.toInt()
        val result = if (len > 0) outBuf.value!!.readBytes(len) else ByteArray(0)
        mongreldb_kit_free_arrow(outBuf.value, outLen.value)
        result
    }

    /** Run the Kit migration runner from a JSON array of migrations. */
    fun migrate(migrationsJson: String) = memScoped {
        val rc = mongreldb_kit_migrate_json(db, migrationsJson.cstr.ptr)
        checkRc(rc, "migrate")
    }

    /** Read applied migrations as a JSON array. */
    fun appliedMigrations(): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_applied_migrations_json(db, outJson.ptr)
        checkRc(rc, "appliedMigrations")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
        result
    }

    /** Run a SELECT query (JSON Kit query AST) and return JSON rows. */
    fun querySelect(queryJson: String): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_select_json(db, queryJson.cstr.ptr, outJson.ptr)
        checkRc(rc, "querySelect")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
        result
    }

    /** Run an INSERT query (JSON Kit query AST) and return JSON returning values. */
    fun queryInsert(queryJson: String): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_insert_json(db, queryJson.cstr.ptr, outJson.ptr)
        checkRc(rc, "queryInsert")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
        result
    }

    /** Run an UPDATE query (JSON Kit query AST) and return JSON returning values. */
    fun queryUpdate(queryJson: String): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_update_json(db, queryJson.cstr.ptr, outJson.ptr)
        checkRc(rc, "queryUpdate")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
        result
    }

    /** Run an UPSERT query (JSON Kit query AST) and return JSON returning values. */
    fun queryUpsert(queryJson: String): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_upsert_json(db, queryJson.cstr.ptr, outJson.ptr)
        checkRc(rc, "queryUpsert")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
        result
    }

    /** Run a DELETE query (JSON Kit query AST) and return JSON returning values. */
    fun queryDelete(queryJson: String): String = memScoped {
        val outJson = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_delete_json(db, queryJson.cstr.ptr, outJson.ptr)
        checkRc(rc, "queryDelete")
        val result = outJson.value?.toKString() ?: "[]"
        mongreldb_kit_free_json(outJson.value)
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
