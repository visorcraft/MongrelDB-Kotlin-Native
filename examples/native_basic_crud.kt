// SPDX-License-Identifier: MIT OR Apache-2.0
/*
 * Example: basic CRUD with the native embedded MongrelDB Kit engine (Tier 1).
 *
 * Unlike the HTTP client (MongrelDB.kt), this links libmongreldb_kit +
 * libmongreldb directly via cinterop and runs the engine in-process. No
 * daemon needed.
 *
 * ---- Prerequisites ---------------------------------------------------------
 *
 * 1. Download the prebuilt native libraries from
 *    https://github.com/visorcraft/MongrelDB/releases
 *    (mongreldb-native-* and mongreldb-kit-native-* archives):
 *
 *      curl -fsSL -o native.tar.gz \
 *        https://github.com/visorcraft/MongrelDB/releases/download/v0.60.2/mongreldb-native-linux-x64-gnu.tar.gz
 *      tar xzf native.tar.gz        # -> mongreldb-native/{lib,include}/
 *
 *      curl -fsSL -o kit-native.tar.gz \
 *        https://github.com/visorcraft/MongrelDB/releases/download/v0.60.2/mongreldb-kit-native-linux-x64-gnu.tar.gz
 *      tar xzf kit-native.tar.gz    # -> mongreldb-kit-native/{lib,include}/
 *
 * 2. Put the headers where cinterop can find them (the .def file references
 *    mongreldb_kit.h), and the libraries on the linker search path, e.g.:
 *
 *      export MONGRELDB_NATIVE_DIR=$PWD/mongreldb-native/lib
 *      # (mingw users: place mongreldb_kit.lib + mongreldb.lib on the path)
 *
 * 3. Build with the native source set enabled:
 *
 *      ./gradlew linkReleaseExecutableLinuxX64 -PenableNative=true
 *      ./build/bin/native/releaseExecutable/demo.kexe
 *
 *    The `-PenableNative=true` Gradle property runs cinterop against
 *    mongreldb_kit.h and compiles NativeDB.kt into the binary. Without it,
 *    NativeDB is excluded and this example will not compile.
 *
 * This example: creates a database with a JSON schema, inserts via SQL,
 * selects JSON rows, runs Arrow IPC, migrates, uses the Kit query builder,
 * reads back applied migrations - all in-process.
 */

@file:kotlin.OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.visorcraft.mongreldb.examples

import com.visorcraft.mongreldb.NativeDB
import com.visorcraft.mongreldb.QueryException
import kotlinx.cinterop.ExperimentalForeignApi

private val SCHEMA_JSON = """
    {"tables":[{"id":1,"name":"users",
    "columns":[
    {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
    {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false},
    {"id":3,"name":"email","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
    ],"primary_key":["id"]}]}
""".trimIndent()

private val ADD_ORDERS_MIGRATION = """
    [{"version":1,"name":"add_orders","ops":[
    {"raw_sql":"CREATE TABLE orders (id INT64 PRIMARY KEY, user_id INT64, total FLOAT64)"}]}]
""".trimIndent()

// A no-filter Kit SELECT AST: all columns, no filter/order, no limit/offset.
private val SELECT_ALL_USERS = """
    {"table":"users","columns":[],"filter":null,"order_by":[],"limit":null,"offset":null}
""".trimIndent()

fun main() {
    val dbdir = "/tmp/mdb_native_kt_${platform.posix.getpid()}"

    println("=== Native Embedded Basic CRUD (Kotlin/Native + Kit FFI) ===")
    println("Database dir: $dbdir\n")

    // Verify the native library is loadable before doing anything else.
    if (!NativeDB.nativeAvailable()) {
        System.err.println("libmongreldb_kit is not loadable. Did you pass " +
            "-PenableNative=true and set the library path?")
        kotlin.system.exitProcess(1)
    }

    // 1. Create the Kit database with a JSON schema. `use {}` frees the FFI
    //    handle (mongreldb_kit_database_free) on close.
    NativeDB.create(dbdir, SCHEMA_JSON).use { db ->
        println("1. Database created with schema (users table)")

        // 2. Insert rows via SQL. sqlRows returns JSON; for DML that is "[]".
        db.sqlRows("INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@example.com')")
        db.sqlRows("INSERT INTO users (id, name, email) VALUES (2, 'Bob', 'bob@example.com')")
        db.sqlRows("INSERT INTO users (id, name, email) VALUES (3, 'Carol', 'carol@example.com')")
        println("2. Inserted 3 rows via SQL")

        // 3. SELECT via SQL (JSON rows).
        val users = db.sqlRows("SELECT id, name, email FROM users ORDER BY id")
        println("3. SELECT all rows:")
        println("   $users")

        // 4. Arrow IPC for columnar reads. Returns raw bytes; the first 6 bytes
        //    are "ARROW1" for a valid IPC stream.
        val arrow = db.sqlArrow("SELECT id FROM users")
        print("4. Arrow IPC: ${arrow.size} bytes")
        if (arrow.size >= 6) {
            val magic = arrow.copyOfRange(0, 6).toString(Charsets.UTF_8)
            print(", magic: $magic")
        }
        println()

        // 5. Migration: add an orders table.
        db.migrate(ADD_ORDERS_MIGRATION)
        println("5. Migration: created 'orders' table")

        // Insert into the migrated table.
        db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (1, 1, 99.99)")
        db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (2, 2, 49.99)")

        // 6. SQL JOIN across both tables.
        val join = db.sqlRows(
            "SELECT u.name, o.total FROM users u " +
            "JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC"
        )
        println("6. SQL JOIN (users + orders):")
        println("   $join")

        // 7. Kit query builder: SELECT.
        val qb = db.querySelect(SELECT_ALL_USERS)
        println("7. Kit query builder SELECT:")
        println("   $qb")

        // 8. Read back applied migrations.
        val applied = db.appliedMigrations()
        println("8. Applied migrations: $applied")
    }

    println("\n=== All operations completed successfully! ===")
}
