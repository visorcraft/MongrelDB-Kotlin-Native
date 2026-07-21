<p align="center">
  <img src="assets/mongrel.png" alt="MongrelDB logo" width="250" />
</p>

<h1 align="center">MongrelDB Kotlin/Native Client</h1>

<p align="center">
  <b>Kotlin/Native HTTP + embedded client for MongrelDB - the embedded+server database with SQL, vector search, full-text search, and AI-native retrieval.</b>
  <br />
  Compiles to native machine code (no JVM). HTTP mode talks to <code>mongreldb-server</code> via ktor-client-curl; native mode links <code>libmongreldb_kit</code> + <code>libmongreldb</code> directly for in-process engine access with zero serialization overhead.
</p>

<p align="center">
  <a href="https://github.com/visorcraft/MongrelDB-Kotlin-Native/actions/workflows/ci.yml"><img src="https://github.com/visorcraft/MongrelDB-Kotlin-Native/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/visorcraft/MongrelDB/releases"><img src="https://img.shields.io/badge/server-v0.63.0-blue.svg" alt="MongrelDB server" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License" /></a>
</p>

## Package

| Surface | Package | Install |
|---|---|---|
| Kotlin/Native HTTP client | `com.visorcraft:mongreldb-kotlin-native` | Gradle (KMP) + `mongreldb-server` daemon |
| Kotlin/Native embedded (Tier 1) | `com.visorcraft:mongreldb-kotlin-native` (native) | Gradle with `-PenableNative=true` + prebuilt native libs |

History retention: `historyRetention()` and `setHistoryRetentionEpochs()` expose
`GET`/`PUT /history/retention`.

## Requirements

- **Kotlin/Native toolchain** via Gradle (Kotlin 2.1.21 or newer, bundled with the Gradle plugin). The Kotlin Multiplatform plugin produces `.klib` artifacts for `linuxX64`, `macosX64`, `macosArm64`, and `mingwX64`.
- **Gradle 8.0 or newer** (the Gradle wrapper in this repo is preconfigured).
- **libcurl** with headers (the native curl engine for ktor). On Debian/Ubuntu install `libcurl4-openssl-dev`; on Fedora `curl-devel`; on macOS it ships with the system.
- A running [`mongreldb-server`](https://github.com/visorcraft/MongrelDB) daemon (for HTTP mode).
- For native embedded mode: the prebuilt `libmongreldb` + `libmongreldb_kit` archives on the linker path (see [Native embedding](#native-embedding-tier-1)).

## What It Provides

- **Typed CRUD** over the Kit transaction endpoint: `put`, `upsert` (insert-or-update on PK conflict), `delete` by row id or primary key, with idempotency keys for safe retries. Values are a sealed `Value` type (`Null`, `Bool`, `Int64`, `Float64`, `Text`) - no untyped unions, no stringly-typed cells.
- **Query builder** that pushes conditions down to the engine's specialized indexes for sub-millisecond lookups: primary-key, bitmap equality, learned-range, null checks, and FM-index full-text search. Conditions are AND-ed. Results decode into typed `Row`/`Cell` values directly.
- **Idempotent batch transactions** - operations staged as `MongrelDB.Op` and committed atomically via `commit`, with the engine enforcing unique, foreign-key, and check constraints at commit time. Idempotency keys return the original response on duplicate commits, even after a crash.
- **Full SQL access** through the DataFusion-backed `/sql` endpoint: recursive CTEs, window functions, `CREATE TABLE AS SELECT`, materialized views, and multi-statement execution.
- **Schema management**: typed table creation with column factory methods (`Column.int64`, `.text`, `.float64`, `.bool`, `.varchar`), enum variants, default values/expressions, the full schema catalog, and per-table descriptors. Optional native `constraints` JSON (CHECKs, unique, foreign keys).
- **History retention** management: inspect and resize the rolling MVCC history window at runtime.
- **Native Tier-1 embedding**: link `libmongreldb_kit` + `libmongreldb` directly via `cinterop` and run the engine in-process with zero serialization overhead. SQL (JSON rows and Arrow IPC bytes), migrations, and the Kit query builder (`querySelect`/`Insert`/`Update`/`Upsert`/`Delete`) are all available through the FFI.
- **Typed exception hierarchy**: `AuthException` (401/403), `NotFoundException` (404), `ConflictException` (409, with structured `code` and `opIndex`), and `QueryException` (400/5xx/transport). Mirrors the C++ and Kotlin/JVM clients.

## Examples

Runnable, commented examples live in `examples/` and the docs:

- [Quickstart](docs/quickstart.md) - install Kotlin/Native, start the daemon, write and run a complete program.
- [Transactions](docs/transactions.md) - batch commits, idempotency keys, constraint handling.
- [Queries](docs/queries.md) - every native condition type and the index it pushes down to.
- [SQL](docs/sql.md) - recursive CTEs, window functions, advanced SQL.
- [Authentication](docs/auth.md) - bearer token, HTTP Basic, and open modes.
- [Errors](docs/errors.md) - the exception hierarchy and recovery patterns.

## Quick Example

```kotlin
import com.visorcraft.mongreldb.*

fun main() {
    // Connect to a running mongreldb-server daemon.
    MongrelDB().use { db ->
        // Create a table. Column ids are stable on-wire identifiers.
        val cols = listOf(
            Column.int64("id", 1, primaryKey = true),
            Column.varchar("customer", 2, nullable = false),
            Column.float64("amount", 3, nullable = false),
        )
        db.createTable("orders", cols)

        // Insert rows (cells pair column id + value).
        db.put("orders", listOf(
            InputCell(1, Value.int64(1)),
            InputCell(2, Value.text("Alice")),
            InputCell(3, Value.float64(99.5)),
        ))
        db.put("orders", listOf(
            InputCell(1, Value.int64(2)),
            InputCell(2, Value.text("Bob")),
            InputCell(3, Value.float64(150.0)),
        ))

        // Query with a native index condition (learned-range index).
        val result = db.query(
            table = "orders",
            conditions = listOf(Condition.Range(columnId = 3, lo = 100.0)),
            projection = listOf(1L, 3L),
            limit = 100,
        )
        println("rows: ${result.rows.size}")

        println("count: ${db.count("orders")}") // 2

        // Run SQL.
        db.sql("UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'")
    }
}
```

## Authentication

```kotlin
// Bearer token (--auth-token mode)
val db = MongrelDB().withToken("my-secret-token")

// HTTP Basic (--auth-users mode)
val db2 = MongrelDB().withBasicAuth("admin", "s3cret")
```

A token takes precedence over basic auth if both are supplied. See
[auth.md](docs/auth.md).

## History retention

`historyRetention()` and `setHistoryRetentionEpochs()` expose `GET
/history/retention` and `PUT /history/retention`. They inspect and change the
rolling MVCC history window, measured in committed epochs:

```kotlin
MongrelDB().use { db ->
    val ret = db.historyRetention()
    println("retain ${ret.historyRetentionEpochs} epochs; " +
            "earliest retained epoch ${ret.earliestRetainedEpoch}")

    val updated = db.setHistoryRetentionEpochs(4096UL)
}
```

When catalog authentication is enabled, both routes require the `ADMIN`
permission. Increasing the retention window cannot restore history that was
already pruned; the wider guarantee only applies from the current epoch forward.

## Batch transactions

Operations are staged locally and committed atomically. The engine enforces
unique, foreign key, and check constraints at commit time.

```kotlin
MongrelDB().use { db ->
    val ops = listOf(
        MongrelDB.Op(
            type = MongrelDB.Op.OpType.PUT,
            table = "orders",
            cells = listOf(
                InputCell(1, Value.int64(10)),
                InputCell(2, Value.text("Dave")),
                InputCell(3, Value.float64(50.0)),
            ),
        ),
        MongrelDB.Op(
            type = MongrelDB.Op.OpType.PUT,
            table = "orders",
            cells = listOf(
                InputCell(1, Value.int64(11)),
                InputCell(2, Value.text("Eve")),
                InputCell(3, Value.float64(75.0)),
            ),
        ),
        MongrelDB.Op(
            type = MongrelDB.Op.OpType.DELETE_BY_PK,
            table = "orders",
            pkValue = Value.int64(2),
        ),
    )

    // Atomic - all or nothing. The idempotency key makes it safe to retry.
    try {
        db.commit(ops, idempotencyKey = "batch-1")
    } catch (e: ConflictException) {
        System.err.println("constraint violated: ${e.message}")
    }
}
```

See [transactions.md](docs/transactions.md).

## Native query builder

Conditions push down to the engine's specialized indexes. Each `Condition`
targets one index; multiple conditions are AND-ed.

```kotlin
// Bitmap equality (low-cardinality columns)
Condition.BitmapEq(columnId = 2, value = "Alice")

// Range query (learned-range index)
Condition.Range(columnId = 3, lo = 50.0, hi = 150.0)

// Full-text search (FM-index)
Condition.FmContains(columnId = 2, pattern = "database performance")

val result = db.query(
    table = "orders",
    conditions = listOf(
        Condition.BitmapEq(columnId = 2, value = "Alice"),
        Condition.Range(columnId = 3, lo = 50.0, hi = 150.0),
    ),
    projection = listOf(1L, 3L),
    limit = 100,
)
if (result.truncated) {
    // result set hit the limit; more matches exist on the server
}
```

See [queries.md](docs/queries.md).

## Schema constraints

Optional fields on `Column` let you constrain what goes into a column at create
time. They are omitted from the wire JSON when left unset, so existing schemas
are unaffected.

```kotlin
val cols = listOf(
    Column.int64("id", 1, primaryKey = true),
    Column.varchar("customer", 2, nullable = false),
    Column(
        id = 3, name = "created_at",
        storageType = "timestamp_nanos", nullable = false,
        defaultExpr = "now",
    ),
    Column(
        id = 4, name = "status",
        storageType = "varchar", nullable = false,
        enumVariants = listOf("active", "inactive", "paused"),
        defaultValueJson = "\"active\"",
    ),
    Column(
        id = 5, name = "attempts",
        storageType = "int64", nullable = false,
        defaultValueJson = "0",
    ),
    Column(
        id = 6, name = "enabled",
        storageType = "bool", nullable = false,
        defaultValueJson = "true",
    ),
)
```

`enumVariants` sends the `enum_variants` array; both null/absent means
"unrestricted". `defaultValueJson` sends a caller-validated raw JSON scalar such
as `"\"draft\""`, `"7"`, `"true"`, or `"null"`, preserving the literal JSON type
on the wire. `defaultExpr` sends a dynamic default such as `"now"` or `"uuid"`
and takes precedence over static defaults. The legacy `defaultValue` field still
sends a plain string when used. The constraint is enforced server-side, so a row
whose value falls outside the listed variants surfaces as `ConflictException` on
`put`/`commit`.

Table CHECKs use the additive constraints overload. The JSON is the daemon's
native `constraints` object:

```kotlin
val constraints = """
    {"checks":[{"id":1,"name":"amount_nonneg","expr":
      {"Ge":[{"Col":3},{"Lit":{"Float64":0.0}}]}}]}
""".trimIndent()
db.createTable("orders", cols, constraints)
```

## SQL

```kotlin
db.sql("INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)")
db.sql("CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500")

// Recursive CTEs and window functions
db.sql(
    "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<10) " +
    "SELECT n FROM r"
)
db.sql(
    "SELECT id, ROW_NUMBER() OVER (PARTITION BY customer ORDER BY amount DESC) " +
    "FROM orders"
)
```

See [sql.md](docs/sql.md).

## ANN index backends

The engine's `ann` index is swappable across three backends - `hnsw` (the default), `diskann`, and `ivf` - selected with the `algorithm` option. Quantization is independently configurable: `dense`, `binary_sign`, or `product` (product quantization, with `num_subvectors`, `bits_per_subvector`, `pq_training_samples`, `pq_seed`, and `pq_rerank_factor`). These are ordinary DDL strings run through `sql`, so no client changes are needed.

```kotlin
// DiskANN (in-memory Vamana graph)
db.sql("CREATE INDEX orders_emb_diskann ON orders USING ann (embedding) WITH (algorithm = 'diskann', quantization = 'dense', diskann_l = 50, diskann_r = 64, beam_width = 8)")

// IVF with dense vectors (clustered)
db.sql("CREATE INDEX orders_emb_ivf ON orders USING ann (embedding) WITH (algorithm = 'ivf', quantization = 'dense', nlist = 1024, nprobe = 16)")

// HNSW with product quantization (recall-tuned)
db.sql("CREATE INDEX orders_emb_hnsw_pq ON orders USING ann (embedding) WITH (algorithm = 'hnsw', quantization = 'product', m = 16, ef_construction = 200, ef_search = 50, num_subvectors = 32, pq_training_samples = 50000, pq_rerank_factor = 8)")
```


## User & role management

User, role, and permission management is performed through SQL against the
daemon's catalog. Passwords are Argon2id-hashed server-side.

```kotlin
db.sql("CREATE USER admin WITH PASSWORD 's3cret-pw'")
db.sql("ALTER USER admin SET ADMIN TRUE")

db.sql("CREATE ROLE analyst")
db.sql("GRANT select ON orders TO analyst")  // table-level
db.sql("GRANT analyst TO alice")

db.sql("SELECT username FROM catalog.users")  // list users
db.sql("SELECT name FROM catalog.roles")      // list roles
```

## Error handling

All client errors are unchecked subclasses of `MongrelDBException`. Catch the
category you care about; let the rest propagate.

```kotlin
try {
    val body = db.schemaFor("missing_table")
} catch (e: NotFoundException) {
    System.err.println("table does not exist: ${e.message}")
} catch (e: ConflictException) {
    System.err.println("unexpected conflict on a read: ${e.message} (code=${e.code})")
} catch (e: AuthException) {
    System.err.println("not authorized: ${e.message}")
} catch (e: MongrelDBException) {
    System.err.println("error: ${e.message}")
}
```

See [errors.md](docs/errors.md).

## API reference

### Client lifecycle

| Function | Description |
|----------|-------------|
| `MongrelDB(url)` | Construct an HTTP client (default `http://127.0.0.1:8453`) |
| `MongrelDB().withToken(token, url)` | Bearer token auth (`--auth-token` mode) |
| `MongrelDB().withBasicAuth(user, pass, url)` | HTTP Basic auth (`--auth-users` mode) |
| `MongrelDB(url, authHeader, timeoutSeconds)` | Full constructor (auth header pair, timeout) |
| `close()` | Release the underlying ktor/libcurl client (implements `AutoCloseable`) |

### Database operations

| Function | Description |
|----------|-------------|
| `health()` | Check daemon health |
| `tableNames()` | List table names |
| `createTable(name, columns)` | Create a table; column descriptors may carry enum/default fields |
| `createTable(name, columns, constraintsJson)` | Create a table with native `constraints` JSON (including CHECKs) |
| `createTable(name, columns, constraintsJson, indexesJson)` | Create a table with all six index kinds and options |
| `historyRetention()` | Inspect the MVCC history retention window |
| `setHistoryRetentionEpochs(epochs)` | Resize the MVCC history retention window |
| `dropTable(name)` | Drop a table |
| `count(table)` | Row count |
| `put(table, cells, idempotencyKey?)` | Insert a row |
| `upsert(table, cells, updateCells?, idempotencyKey?)` | Upsert a row |
| `delete(table, rowId)` | Delete by row id |
| `deleteByPk(table, pk)` | Delete by primary key |
| `commit(ops, idempotencyKey?)` | Commit a batch atomically |
| `query(table, conditions?, projection?, limit?)` | Run a native query |
| `sql(statement)` | Execute SQL |
| `schema()` | Full schema catalog (raw JSON) |
| `schemaFor(table)` | Single-table descriptor (raw JSON) |

### Types

| Type | Description |
|------|-------------|
| `Value` (sealed) | `Null`, `Bool`, `Int64`, `Float64`, `Text`; factory helpers `Value.bool/int64/float64/text` |
| `Cell(columnId, value)` | One cell in a result row |
| `Row(cells)` | A result row; `get(id)`, `getString(id)`, `getLong(id)` accessors |
| `Result(rows, truncated)` | Result set with a truncation flag |
| `Column(...)` | Column definition; factories `int64/text/float64/bool/varchar` |
| `InputCell(columnId, value)` | A cell supplied to a write operation |
| `Condition` (sealed) | `PrimaryKeyInt`, `PrimaryKeyString`, `BitmapEq`, `Range`, `FmContains`, `IsNull`, `IsNotNull` |
| `HistoryRetention` | MVCC retention window config |

### Native embedded (`NativeDB`, opt-in via `-PenableNative=true`)

| Function | Description |
|----------|-------------|
| `NativeDB.open(path)` | Open an existing Kit database |
| `NativeDB.create(path, schemaJson)` | Create a fresh Kit database with a JSON schema |
| `NativeDB.nativeAvailable()` | Check whether the native library is loadable |
| `sqlRows(sql)` | Run SQL, return JSON rows |
| `sqlArrow(sql)` | Run SQL, return Arrow IPC bytes |
| `migrate(migrationsJson)` | Run the Kit migration runner |
| `appliedMigrations()` | Read applied migrations as JSON |
| `querySelect(queryJson)` | Kit query builder SELECT |
| `queryInsert(queryJson)` | Kit query builder INSERT |
| `queryUpdate(queryJson)` | Kit query builder UPDATE |
| `queryUpsert(queryJson)` | Kit query builder UPSERT |
| `queryDelete(queryJson)` | Kit query builder DELETE |

## Building and testing

This is a Kotlin Multiplatform project. The Gradle wrapper is included, so no
local Gradle install is required.

```sh
# Build all native targets as a klib
./gradlew build

# Run the unit + wire-shape tests
./gradlew check

# Run the live integration tests. Set MONGRELDB_URL to point at a running
# daemon; tests self-skip when no daemon is reachable.
MONGRELDB_URL=http://127.0.0.1:8453 ./gradlew linuxX64Test
```

Fetch a prebuilt server binary from the [MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.63.0/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

### Consuming the client from another KMP project

```kotlin
// settings.gradle.kts
includeBuild("path/to/MongrelDB-Kotlin-Native")

// build.gradle.kts
kotlin {
    linuxX64() { /* your target */ }
    sourceSets["nativeMain"].dependencies {
        implementation("com.visorcraft:mongreldb-kotlin-native")
    }
}
```

### Compiling an example to a native binary

```sh
./gradlew compileKotlinLinuxX64   # produces build/klib/.../mongreldb-kotlin-native.klib
```

## Native embedding (Tier 1)

For in-process access with zero serialization overhead, link the prebuilt
`libmongreldb` (core engine) and `libmongreldb_kit` (schema model, migrations,
query builder) instead of connecting to a daemon. The Kotlin/Native client
exposes this through `NativeDB`, which calls the same Kit C ABI as the C/C++
clients via `cinterop`.

Download the prebuilt libraries from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases) page:

```sh
# Download for your platform, e.g. linux-x64-gnu
curl -fsSL -o native.tar.gz \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.63.0/mongreldb-native-linux-x64-gnu.tar.gz
tar xzf native.tar.gz  # produces mongreldb-native/{lib,include}/

curl -fsSL -o kit-native.tar.gz \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.63.0/mongreldb-kit-native-linux-x64-gnu.tar.gz
tar xzf kit-native.tar.gz  # produces mongreldb-kit-native/{lib,include}/
```

Point the build at them and enable the native source set:

```sh
export MONGRELDB_NATIVE_DIR=$PWD/mongreldb-native/lib
./gradlew build -PenableNative=true
```

When `-PenableNative=true` is set, the Gradle build runs `cinterop` against the
Kit header (`src/nativeInterop/cinterop/mongreldb_kit.def`), generates Kotlin
FFI bindings, and compiles `NativeDB` into the klib. When the flag is absent,
`NativeDB.kt` is excluded from compilation and the klib ships HTTP-only - so CI
and HTTP-only consumers need no native libraries on the link path.

`NativeDB` uses `Rc<RefCell>` internally and is single-threaded. Create one per
worker; do not share across threads.

```kotlin
import com.visorcraft.mongreldb.*

fun main() {
    val schema = """
        {"tables":[{"id":1,"name":"users","columns":[
          {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
          {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
        ],"primary_key":["id"]}]}
    """.trimIndent()

    NativeDB.create("/tmp/mdb-native-demo", schema).use { db ->
        db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'Alice')")
        println(db.sqlRows("SELECT id, name FROM users ORDER BY id"))

        db.migrate("""
            [{"version":1,"name":"add_orders","ops":[
              {"raw_sql":"CREATE TABLE orders (id INT64 PRIMARY KEY, user_id INT64, total FLOAT64)"}]}]
        """.trimIndent())
        println(db.appliedMigrations())
    }
}
```

See the FFI crate's [`docs/migrations.md`](https://github.com/visorcraft/MongrelDB/blob/master/crates/mongreldb-ffi/docs/migrations.md)
for the full `MigrationOp` to FFI call mapping when running migrations via the
native ABI.

## Contributing

Contributions are welcome. Please:

1. Open an issue first for non-trivial changes.
2. Add focused tests near your change - `./gradlew check` must stay green.
3. Keep the code idiomatic Kotlin, warning-clean.
4. Match the existing style: 4-space indent, `PascalCase` types, `camelCase`
   functions and locals, sealed classes for closed hierarchies.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow.

## License

Dual-licensed under the **MIT License** or the **Apache License, Version 2.0**,
at your option. See [MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE) for the full text.

`SPDX-License-Identifier: MIT OR Apache-2.0`
