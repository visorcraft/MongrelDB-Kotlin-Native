# Transactions

MongrelDB commits every write through a single atomic transaction endpoint
(`POST /kit/txn`). This guide covers the two ways to use it - a one-shot
single op, and a staged batch - plus idempotency keys for safe retries and
constraint-violation handling.

The engine enforces `UNIQUE`, foreign-key, check, and trigger constraints at
**commit time**. A violation aborts the entire batch: no op in the batch
becomes visible, and the client surfaces a `ConflictException`.

---

## Single puts vs. batch transactions

### Single op: `put`

`put` is a convenience wrapper that sends a one-op transaction. Use it when a
write is independent and you do not need atomicity across multiple rows.

```kotlin
MongrelDB().use { db ->
    try {
        db.put("orders", listOf(
            InputCell(1, Value.int64(1)),
            InputCell(2, Value.text("Alice")),
            InputCell(3, Value.float64(99.5)),
        ))
    } catch (e: ConflictException) {
        System.err.println("put failed: ${e.message}")
    }
}
```

`upsert`, `delete`, and `deleteByPk` are the same shape: single-op
transactions. They each accept an optional `idempotencyKey` as their last
argument.

### Batch: `commit`

When several writes must succeed or fail together, stage them as `MongrelDB.Op`
values and commit once. All ops go to the server in a single HTTP request and
commit atomically.

```kotlin
MongrelDB().use { db ->
    val ops = listOf(
        MongrelDB.Op(
            type = MongrelDB.Op.OpType.PUT,
            table = "orders",
            cells = listOf(
                InputCell(1, Value.int64(10)),
                InputCell(2, Value.text("Dave")),
            ),
        ),
        MongrelDB.Op(
            type = MongrelDB.Op.OpType.PUT,
            table = "orders",
            cells = listOf(
                InputCell(1, Value.int64(11)),
                InputCell(2, Value.text("Eve")),
            ),
        ),
        MongrelDB.Op(
            type = MongrelDB.Op.OpType.DELETE_BY_PK,
            table = "orders",
            pkValue = Value.int64(2),
        ),
    )

    db.commit(ops)
}
```

`Op.OpType.UPSERT` takes an additional `updateCells` list applied on a
primary-key conflict. An empty `updateCells` means "do nothing on conflict".

### The `Op` type

`MongrelDB.Op` is a data class with an `OpType` enum:

| `OpType` | Fields used | Wire op |
|----------|-------------|---------|
| `PUT` | `table`, `cells` | `put` |
| `UPSERT` | `table`, `cells`, `updateCells` | `upsert` |
| `DELETE` | `table`, `rowId` | `delete` (by internal row id) |
| `DELETE_BY_PK` | `table`, `pkValue` | `delete_by_pk` |

## Idempotency keys for safe retries

Networks drop requests and daemons crash after committing but before replying.
An idempotency key makes a commit safe to retry: the daemon remembers the key
and replays the **original** result on a duplicate commit, even across
restarts.

Pass the key as the last argument to `commit` (or `put` / `upsert`):

```kotlin
MongrelDB().use { db ->
    // A handler that must not double-charge, even if the client retries or the
    // connection drops after the daemon committed.
    val charge = listOf(
        InputCell(1, Value.text(orderId)),
        InputCell(2, Value.float64(199.0)),
    )
    val op = MongrelDB.Op(
        type = MongrelDB.Op.OpType.PUT,
        table = "charges",
        cells = charge,
    )

    // Use a stable, business-meaningful key derived from the request. On a retry
    // with the same key the daemon returns the first commit's result instead of
    // inserting a second row.
    db.commit(listOf(op), idempotencyKey = "charge-order-123")
}
```

Rules for keys:

- Any non-empty string works. Prefer content-derived, globally-unique values
  (e.g. `"charge:$orderId"`).
- `null` (the default) disables idempotency - a retry will commit again.
- The key scopes the **entire batch**, not individual ops. Reuse the exact
  same ops and key together when retrying.

## Handling constraint violations

Constraint violations arrive as HTTP 409, mapped to `ConflictException`. The
daemon's error envelope carries a structured `code` and an `op_index`, which
the client decodes onto the exception:

```json
{"status": "aborted", "error": {"code": "UNIQUE_VIOLATION", "message": "...", "op_index": 0}}
```

Catch the category and read the fields:

```kotlin
try {
    db.commit(ops)
} catch (e: ConflictException) {
    System.err.println("constraint violated (code=${e.code}, opIndex=${e.opIndex}): ${e.message}")
    // The engine already rolled back the whole batch. Nothing to undo.
} catch (e: AuthException) {
    System.err.println("not authorized: ${e.message}")
} catch (e: MongrelDBException) {
    System.err.println("commit failed: ${e.message}")
}
```

Structured codes you will commonly see:

| code | Meaning |
|------|---------|
| `UNIQUE_VIOLATION` | A unique/PK constraint rejected the commit |
| `FK_VIOLATION` | A foreign-key reference was missing |
| `CHECK_VIOLATION` | A check constraint or trigger rejected the commit |
| `NOT_FOUND` | A named resource (table, schema) does not exist |

## Rollback

There are two notions of "rollback":

1. **Server-side.** When `commit` fails with `ConflictException`, the engine
   has already discarded the entire batch. Nothing was written; there is no
   server rollback to perform.
2. **Client-side.** Because ops are staged in your own `List<Op>`, discarding
   them is just a matter of not calling `commit`. There is no transaction
   handle to roll back - the batch only exists once you send it.

```kotlin
val ops = mutableListOf<MongrelDB.Op>()
// ... stage ops ...
if (!businessRuleOk()) {
    // Don't commit. The daemon has seen nothing.
    return
}
db.commit(ops)
```

## Summary

| Goal | Use |
|------|-----|
| One independent write | `put` / `upsert` / `delete` / `deleteByPk` |
| Several writes that must commit together | `commit` with a `List<Op>` |
| Retry safely after a network blip | `commit` with a stable `idempotencyKey` |
| Distinguish constraint classes | Catch `ConflictException`, read `code` / `opIndex` |
| Abort before sending | Don't call `commit` - the batch is local |

See [errors.md](errors.md) for the full exception set and [queries.md](queries.md)
for read patterns.
