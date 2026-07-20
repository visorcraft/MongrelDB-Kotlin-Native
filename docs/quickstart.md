# Quickstart

Zero to a running MongrelDB Kotlin/Native program in fifteen minutes. This guide
assumes a fresh machine and walks through installing the prerequisites, starting
the daemon, and writing, building, and running a complete program.

---

## 1. Prerequisites

You need four things installed: a JDK (for Gradle), libcurl (with headers), the
Kotlin/Native toolchain (fetched by Gradle automatically), and a
`mongreldb-server` daemon.

### Install a JDK, libcurl, and curl dev headers

On Debian/Ubuntu:

```sh
sudo apt install default-jdk libcurl4-openssl-dev
```

On Fedora:

```sh
sudo dnf install java-latest-openjdk libcurl-devel
```

On macOS, the Xcode Command Line Tools provide clang and libcurl; install a JDK
via Homebrew (`brew install openjdk`).

Verify:

```sh
java -version          # 17 or newer
pkg-config --modversion libcurl   # 8.x
```

Gradle itself does not need a separate install - the Gradle wrapper
(`./gradlew`) in this repo boots the right version. The Kotlin Multiplatform
plugin (2.1.21+) downloads the Kotlin/Native compiler on first build.

### Install mongreldb-server

Fetch a prebuilt server binary from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.61.1/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

Verify it runs:

```sh
./bin/mongreldb-server --version
```

## 2. Start the daemon

By default `mongreldb-server` listens on `http://127.0.0.1:8453` and stores
data in the directory you pass as its first argument.

```sh
mkdir -p /tmp/mdb-data
/path/to/mongreldb-server /tmp/mdb-data
```

In another terminal, sanity-check it:

```sh
curl http://127.0.0.1:8453/health
# ok
```

Leave the daemon running for the rest of this guide.

## 3. Build the client

```sh
git clone https://github.com/visorcraft/MongrelDB-Kotlin-Native.git
cd MongrelDB-Kotlin-Native
./gradlew build
```

This compiles the `commonMain` and `nativeMain` source sets into a `.klib` for
your host target (`linuxX64`, `macosX64`/`macosArm64`, or `mingwX64`) and runs
the unit + wire-shape tests.

## 4. Write your first program

Create `demo/src/main.kt` in a fresh Gradle project that consumes the client,
or run the example in this repo:

```sh
mkdir -p demo/src && cd demo
```

`src/main.kt`:

```kotlin
// SPDX-License-Identifier: MIT OR Apache-2.0
import com.visorcraft.mongreldb.*

fun main() {
    // 1. Connect to the daemon. use {} closes the HTTP client at the end.
    MongrelDB().use { db ->
        // 2. Health check before doing anything else.
        check(db.health()) { "daemon not reachable" }
        println("Connected to MongrelDB")

        // 3. Create a table. Each column has a stable numeric id, a name, and a
        //    type. Column 1 is the primary key. Factory helpers cover the common
        //    types; the full Column(...) constructor exposes optional schema
        //    extensions (enum_variants, default_value_json, default_expr, ...).
        val cols = listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.varchar("customer", 2, nullable = false),
            Column(
                id = 3, name = "created_at",
                storageType = "timestamp_nanos", nullable = false,
                defaultExpr = "now",
            ),
            Column(
                id = 4, name = "amount",
                storageType = "float64", nullable = false,
                defaultValueJson = "0.0",
            ),
            Column(
                id = 5, name = "status",
                storageType = "varchar", nullable = false,
                enumVariants = listOf("active", "inactive", "paused"),
                defaultValueJson = "\"active\"",
            ),
        )
        db.createTable("orders", cols)
        println("Created table orders")

        // 4. Insert rows. Cells pair column id + value. The status column is
        //    constrained to {"active","inactive","paused"}; "active" matches the
        //    defaultValueJson literal.
        db.put("orders", listOf(
            InputCell(1, Value.int64(1)),
            InputCell(2, Value.text("Alice")),
            InputCell(4, Value.float64(99.5)),
            InputCell(5, Value.text("active")),
        ))
        db.put("orders", listOf(
            InputCell(1, Value.int64(2)),
            InputCell(2, Value.text("Bob")),
            InputCell(4, Value.float64(150.0)),
            InputCell(5, Value.text("inactive")),
        ))

        // 5. Query with a native index condition. The range index serves this in
        //    sub-millisecond. Projection selects only column ids 1 and 2.
        val result = db.query(
            table = "orders",
            conditions = listOf(Condition.Range(columnId = 4, lo = 100.0)),
            projection = listOf(1L, 2L),
            limit = 100,
        )
        for (row in result.rows) {
            println("id=${row.getLong(1)}, customer=${row.getString(2)}")
        }

        // 6. Count the rows.
        println("total rows: ${db.count("orders")}")
    }
}
```

`build.gradle.kts` (consume the client via an included build):

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.21"
}

repositories { mavenCentral() }

kotlin {
    linuxX64() { binaries.executable { entryPoint = "main" } }  // or your host target

    sourceSets["nativeMain"].dependencies {
        implementation("com.visorcraft:mongreldb-kotlin-native")
    }
}

includeBuild("../MongrelDB-Kotlin-Native")
```

Build and run it:

```sh
./gradlew linkReleaseExecutableLinuxX64
./build/bin/native/releaseExecutable/demo.kexe
```

You should see the row count of 2.

## 5. What each part does

| Code | What it does |
|------|--------------|
| `MongrelDB(url)` | Builds an HTTP client targeting one daemon. One per thread (not thread-safe). Implements `AutoCloseable`. |
| `db.health()` | GET `/health`; returns `true` when the daemon answers. Always check before real work. |
| `db.createTable(name, cols)` | POST `/kit/create_table`. Column `id`s are the on-wire identifiers; use them everywhere else. |
| `Column.int64/text/...` | Factory helpers for the common storage types. `primaryKey` and `nullable` are the most-used flags. |
| `col.enumVariants` | Optional. Constrains a text/varchar column to a fixed value set; server-enforced on commit, surfaces as `ConflictException` on a row outside the set. null = absent. |
| `col.defaultValueJson` | Optional caller-validated raw static JSON scalar, e.g. `"\"draft\""`, `"7"`, `"true"`, `"null"`. The literal JSON type is preserved on the wire. null = absent. |
| `col.defaultExpr` | Optional dynamic default: `"now"` or `"uuid"`. Takes precedence. null = absent. |
| `col.defaultValue` | Legacy string-only default. null = absent. |
| `db.put(table, cells)` | Single-op transaction: POST `/kit/txn` with one `put` op. |
| `db.query(...)` | Builds a `/kit/query` body. Conditions push down to native indexes. |
| `projection = listOf(1, 2)` | Server returns only those column ids, saving bandwidth. |
| `limit = 100` | Caps the result; check `result.truncated` afterward to detect overflow. |
| `row.getLong(id)` / `row.getString(id)` | Typed accessors on a result row; return null when the cell is absent or the wrong tag. |

## 6. History retention

The daemon keeps a rolling window of prior MVCC commit epochs. Use
`historyRetention()` and `setHistoryRetentionEpochs()` to inspect or resize it
at runtime:

```kotlin
MongrelDB().use { db ->
    val ret = db.historyRetention()
    println("retain ${ret.historyRetentionEpochs} epochs; " +
            "earliest retained epoch ${ret.earliestRetainedEpoch}")

    db.setHistoryRetentionEpochs(4096UL)
}
```

When catalog authentication is enabled, both routes require the `ADMIN`
permission. Increasing the window cannot restore history that was already
pruned; the wider guarantee only applies from the current epoch forward.
Historical rows are readable through SQL `AS OF EPOCH` as long as their epoch
remains inside the window.

## 7. Common pitfalls

**Using the column name instead of the column id.** Every on-wire API uses the
numeric `id` from `createTable`, never the `name`. Conditions take the `columnId`
(`Long`), not the string name.

**Treating a single `put` as non-transactional.** `put` is a one-op
transaction. A unique constraint violation surfaces as `ConflictException` (HTTP
409), not as a silent no-op.

**Sharing a client across threads.** A `MongrelDB` client is NOT thread-safe.
Create one per coroutine or serialize access externally. Same for `NativeDB`.

**Expecting `sql()` to always return JSON.** The `/sql` endpoint with
`format=json` returns JSON for `SELECT`; some daemon builds stream Arrow IPC.
Use the native query builder (`query`) for typed row retrieval, and use `sql`
for DDL/DML and statements whose success is the signal.

**Pointing at a daemon that requires auth.** If the daemon was started with
`--auth-token` or `--auth-users`, every call throws `AuthException` unless you
build the client with `withToken(...)` or `withBasicAuth(...)`. See
[auth.md](auth.md).

**Assuming `enumVariants` is checked client-side.** The client only emits the
constraint in the wire JSON; the engine enforces it on `put` / `commit` and
throws `ConflictException` for any value outside the set. Validate at the edge
if you need faster feedback.

## Next steps

- [transactions.md](transactions.md) - atomic batches, idempotency, retries
- [queries.md](queries.md) - every native index condition
- [sql.md](sql.md) - recursive CTEs, window functions, `CREATE TABLE AS SELECT`
- [auth.md](auth.md) - bearer tokens, basic auth, user/role management
- [errors.md](errors.md) - the full exception set and recovery patterns
