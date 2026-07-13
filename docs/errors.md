# Error handling

The Kotlin/Native client surfaces failures as unchecked exceptions. Every error
is a subclass of `MongrelDBException` (itself a `RuntimeException`), so you can
catch the category you care about and let the rest propagate. This is the
complete reference: the exception hierarchy, the HTTP-status mapping, the
daemon's error envelope, and recovery patterns for each category.

---

## The exception model

The client uses a sealed hierarchy rooted at `MongrelDBException`:

```
RuntimeException
  └── MongrelDBException
        ├── AuthException          // HTTP 401 or 403
        ├── NotFoundException      // HTTP 404
        ├── ConflictException      // HTTP 409 (unique/fk/check violation)
        └── QueryException         // HTTP 400, 5xx, transport, malformed JSON
```

All four are unchecked, so you never need a `throws` clause. Catch the most
specific type first; `MongrelDBException` is the catch-all. Native-mode
failures (`NativeDB`) surface as `QueryException`, carrying the FFI error
string from `mongreldb_kit_last_error`.

## Exception reference

| Exception | HTTP status | Meaning | Typical cause |
|-----------|-------------|---------|---------------|
| `AuthException` | 401, 403 | authentication/authorization failure | Missing/bad credentials against an auth-enabled daemon |
| `NotFoundException` | 404 | resource not found | Missing table, missing schema, dropped resource |
| `ConflictException` | 409 | constraint violation at commit | Unique, foreign-key, check, or trigger violation |
| `QueryException` | 400, 5xx | query/server/transport failure | Malformed request, server-side failure, network error, malformed JSON |
| `MongrelDBException` | (base) | any client error | Catch-all parent |

`ConflictException` exposes two extra fields decoded from the daemon's error
envelope:

- `code: String?` - the structured server code (e.g. `"UNIQUE_VIOLATION"`).
- `opIndex: Int?` - the index of the offending operation in the batch.

## The daemon's error envelope

When the daemon rejects a request, it returns a JSON envelope. The client
decodes its `message`, `code`, and `op_index` into the exception:

```json
{
  "status": "aborted",
  "error": {
    "code": "UNIQUE_VIOLATION",
    "message": "duplicate key in column 1",
    "op_index": 0
  }
}
```

Structured codes you will commonly see:

| code | Meaning |
|------|---------|
| `UNIQUE_VIOLATION` | A unique/PK constraint rejected the commit |
| `FK_VIOLATION` | A foreign-key reference was missing |
| `CHECK_VIOLATION` | A check constraint or trigger rejected the commit |
| `NOT_FOUND` | A named resource (table, schema) does not exist |

## HTTP status -> exception mapping

| HTTP status | Exception | Notes |
|-------------|-----------|-------|
| 401, 403 | `AuthException` | Bad/missing credentials |
| 404 | `NotFoundException` | Resource not found |
| 409 | `ConflictException` | Constraint violation at commit |
| 400 | `QueryException` | Malformed request / bad query |
| 5xx | `QueryException` | Daemon-side failure |
| other non-2xx | `QueryException` | Catch-all |
| 2xx | (no exception) | Success |

Transport-level failures (libcurl connection refused, timeout, DNS failure)
surface as `QueryException` wrapping the underlying cause.

## Discriminating errors

Catch the specific type; fall back to the parent:

```kotlin
try {
    val body = db.schemaFor("missing_table")
} catch (e: NotFoundException) {
    System.err.println("table does not exist: ${e.message}")
} catch (e: ConflictException) {
    System.err.println("unexpected conflict on a read (code=${e.code}): ${e.message}")
} catch (e: AuthException) {
    System.err.println("bad credentials: ${e.message}")
} catch (e: MongrelDBException) {
    System.err.println("error: ${e.message}")
}
```

## Recovery patterns

### Auth failure - do not retry blindly

A retry will not fix bad credentials. Surface the error to the caller or
operator.

```kotlin
try {
    db.commit(ops)
} catch (e: AuthException) {
    // Refresh credentials from your secret store, or fail fast.
    throw e
}
```

### Not found - fall back, do not crash

For lookups by primary key, a 404 may be a normal "absent" result (when the
table itself is missing). Treat it accordingly.

```kotlin
try {
    db.schemaFor(table)
} catch (e: NotFoundException) {
    // table missing - treat as empty
    return emptyList()
}
```

Note: a `PrimaryKeyInt`/`PrimaryKeyString` query against an existing table
returns zero rows, not a 404; `NotFoundException` here means the table itself
is missing.

### Constraint conflict - the engine already rolled back

```kotlin
try {
    db.commit(ops)
} catch (e: ConflictException) {
    System.err.println("constraint violated (code=${e.code}, opIndex=${e.opIndex}): ${e.message}")
    // The engine already discarded the whole batch. Nothing to undo.
}
```

### Transient failure - retry with an idempotency key

`QueryException` (for network/5xx errors) covers transport and transient
server failures. With an idempotency key, retrying a transaction is safe (see
[transactions.md](transactions.md)).

```kotlin
var lastError: MongrelDBException? = null
for (attempt in 0 until 3) {
    try {
        db.commit(ops, idempotencyKey = "stable-key")
        lastError = null
        break
    } catch (e: AuthException) {
        // not transient - stop
        throw e
    } catch (e: ConflictException) {
        // not transient - stop
        throw e
    } catch (e: QueryException) {
        lastError = e
        // sleep and retry
    }
}
if (lastError != null) throw lastError
```

### Native-mode failure

`NativeDB` throws `QueryException` for any non-zero FFI return code. The
message includes the operation name, the `rc`, and the string from
`mongreldb_kit_last_error()`:

```kotlin
try {
    NativeDB.create("/tmp/db", schema).use { ndb ->
        ndb.sqlRows("SELECT no_such_col FROM t")
    }
} catch (e: QueryException) {
    // sqlRows failed (rc=...): <ffi error message>
    System.err.println(e.message)
}
```

A null handle from `open`/`create` throws `IllegalStateException` via
`checkNotNull`, carrying the last FFI error.

## Quick reference

```kotlin
import com.visorcraft.mongreldb.*

// Category checks:
try { db.count("t") }
catch (e: NotFoundException)   { /* 404 */ }
catch (e: ConflictException)   { /* 409 - e.code, e.opIndex */ }
catch (e: AuthException)       { /* 401/403 */ }
catch (e: QueryException)      { /* 400/5xx/transport */ }
catch (e: MongrelDBException)  { /* catch-all parent */ }

// Conflict detail:
//   e.code     : String?  - "UNIQUE_VIOLATION" | "FK_VIOLATION" | ...
//   e.opIndex  : Int?     - index of the offending op in the batch
```

## Next steps

- [transactions.md](transactions.md) - constraint handling and retries in context
- [auth.md](auth.md) - credential management
