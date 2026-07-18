// SPDX-License-Identifier: MIT OR Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.visorcraft.mongreldb

import com.visorcraft.mongreldb.native.mongreldb_kit_database_t
import com.visorcraft.mongreldb.native.mongreldb_kit_open
import com.visorcraft.mongreldb.native.mongreldb_kit_create
import com.visorcraft.mongreldb.native.mongreldb_kit_open_encrypted
import com.visorcraft.mongreldb.native.mongreldb_kit_create_encrypted
import com.visorcraft.mongreldb.native.mongreldb_kit_open_with_credentials
import com.visorcraft.mongreldb.native.mongreldb_kit_create_with_credentials
import com.visorcraft.mongreldb.native.mongreldb_kit_open_encrypted_with_credentials
import com.visorcraft.mongreldb.native.mongreldb_kit_create_encrypted_with_credentials
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

        /** Opens an AES-256-GCM encrypted Kit database with a passphrase. */
        fun openEncrypted(path: String, passphrase: String): NativeDB {
            val handle = mongreldb_kit_open_encrypted(path, passphrase)
            checkNotNull(handle) { "mongreldb_kit_open_encrypted failed: ${lastError()}" }
            return NativeDB(handle)
        }

        /** Creates an AES-256-GCM encrypted Kit database with a passphrase. */
        fun createEncrypted(path: String, schema: String, passphrase: String): NativeDB {
            val handle = mongreldb_kit_create_encrypted(path, schema, passphrase)
            checkNotNull(handle) { "mongreldb_kit_create_encrypted failed: ${lastError()}" }
            return NativeDB(handle)
        }

        /** Opens a Kit database with storage-layer username/password credentials. */
        fun openWithCredentials(path: String, username: String, password: String): NativeDB {
            val handle = mongreldb_kit_open_with_credentials(path, username, password)
            checkNotNull(handle) { "mongreldb_kit_open_with_credentials failed: ${lastError()}" }
            return NativeDB(handle)
        }

        /** Creates a credentialed Kit database (require_auth + admin user). */
        fun createWithCredentials(
            path: String,
            schema: String,
            adminUsername: String,
            adminPassword: String,
        ): NativeDB {
            val handle = mongreldb_kit_create_with_credentials(
                path, schema, adminUsername, adminPassword
            )
            checkNotNull(handle) { "mongreldb_kit_create_with_credentials failed: ${lastError()}" }
            return NativeDB(handle)
        }

        /** Opens encrypted + credentialed Kit database. */
        fun openEncryptedWithCredentials(
            path: String,
            passphrase: String,
            username: String,
            password: String,
        ): NativeDB {
            val handle = mongreldb_kit_open_encrypted_with_credentials(
                path, passphrase, username, password
            )
            checkNotNull(handle) {
                "mongreldb_kit_open_encrypted_with_credentials failed: ${lastError()}"
            }
            return NativeDB(handle)
        }

        /** Creates encrypted + credentialed Kit database (passphrase + admin user). */
        fun createEncryptedWithCredentials(
            path: String,
            schema: String,
            passphrase: String,
            adminUsername: String,
            adminPassword: String,
        ): NativeDB {
            val handle = mongreldb_kit_create_encrypted_with_credentials(
                path, schema, passphrase, adminUsername, adminPassword
            )
            checkNotNull(handle) {
                "mongreldb_kit_create_encrypted_with_credentials failed: ${lastError()}"
            }
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
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
        result
    }

    fun sqlArrow(sql: String): ByteArray = memScoped {
        val outBuf = alloc<CPointerVar<UByteVar>>()
        val outLen = alloc<ULongVar>()
        val rc = mongreldb_kit_sql_arrow(db, sql, outBuf.ptr, outLen.ptr)
        checkRc(rc, "sqlArrow")
        val len = outLen.useContents { value }.toInt()
        val result = if (len > 0) outBuf.useContents { value }!!.readBytes(len) else ByteArray(0)
        mongreldb_kit_free_arrow(outBuf.useContents { value }, outLen.useContents { value })
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
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
        result
    }

    fun querySelect(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_select_json(db, queryJson, out.ptr)
        checkRc(rc, "querySelect")
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
        result
    }

    fun queryInsert(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_insert_json(db, queryJson, out.ptr)
        checkRc(rc, "queryInsert")
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
        result
    }

    fun queryUpdate(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_update_json(db, queryJson, out.ptr)
        checkRc(rc, "queryUpdate")
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
        result
    }

    fun queryUpsert(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_upsert_json(db, queryJson, out.ptr)
        checkRc(rc, "queryUpsert")
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
        result
    }

    fun queryDelete(queryJson: String): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val rc = mongreldb_kit_query_delete_json(db, queryJson, out.ptr)
        checkRc(rc, "queryDelete")
        val result = out.useContents { value }?.toKString() ?: "[]"
        mongreldb_kit_free_json(out.useContents { value })
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
