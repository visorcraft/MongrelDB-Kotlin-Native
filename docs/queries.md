# Queries

The `query` function pushes conditions down to MongrelDB's native indexes for
sub-millisecond lookups - primary-key, bitmap, learned-range, FM-index full
text, and null checks. Each condition type maps to one specialized index;
conditions are AND-ed together.

```kotlin
val result = db.query(
    table = "orders",
    conditions = listOf(
        Condition.Range(columnId = 3, lo = 100.0, hi = 500.0),
    ),
    projection = listOf(1L, 2L),
    limit = 100,
)
```

This guide covers every condition type, projection, limits and truncation, and
combining conditions.

---

## The basics

Every `query` call takes the table, a list of conditions, a projection, and a
limit:

| Argument | Purpose |
|----------|---------|
| `table` | The table name. |
| `conditions` (default `[]`) | A list of native conditions. All are AND-ed. |
| `projection` (default `[]`) | Return only these column ids (empty means all columns). |
| `limit` (default `0`) | Cap the number of rows. `0` means no cap. |

The request body the client builds matches the daemon's `/kit/query` shape:

```json
{
  "table": "orders",
  "conditions": [{"kind": "range", "column_id": 3, "lo": 100.0, "lo_set": true, "lo_inclusive": true, "hi": 500.0, "hi_set": true, "hi_inclusive": true}],
  "projection": [1, 2],
  "limit": 100
}
```

`Result` is a plain data class: `rows: List<Row>` plus `truncated: Boolean`.
Each `Row` is a list of `Cell(columnId, value)`; `Value` is a sealed class with
`Null`, `Bool`, `Int64`, `Float64`, and `Text` subtypes.

## Reading result values

`Row` offers typed accessors that return `null` when the cell is absent or the
wrong tag:

```kotlin
for (row in result.rows) {
    println("id=${row.getLong(1)}, customer=${row.getString(2)}")
}
```

For full control, iterate the cells and smart-cast on the `Value` subtype:

```kotlin
import com.visorcraft.mongreldb.Value

for (row in result.rows) {
    for (cell in row.cells) {
        when (val v = cell.value) {
            is Value.Int64  -> println("col ${cell.columnId} = ${v.value}")
            is Value.Float64 -> println("col ${cell.columnId} = ${v.value}")
            is Value.Text   -> println("col ${cell.columnId} = ${v.value}")
            is Value.Bool   -> println("col ${cell.columnId} = ${v.value}")
            is Value.Null   -> println("col ${cell.columnId} = null")
        }
    }
}
```

## Condition types

Each `Condition` is a data class. Column references use the numeric **column
id**, never the column name.

### `PrimaryKeyInt` / `PrimaryKeyString` - exact primary-key match

The fastest lookup. Use `PrimaryKeyInt` for integer PKs and
`PrimaryKeyString` for byte/string PKs.

```kotlin
val result = db.query(
    table = "orders",
    conditions = listOf(Condition.PrimaryKeyInt(42)),
)
```

```kotlin
val result = db.query(
    table = "documents",
    conditions = listOf(Condition.PrimaryKeyString("doc-abc")),
)
```

### `Range` - numeric range (learned-range index)

`lo` and `hi` are `Double?`; leave either `null` for an open end. Bounds are
inclusive by default (`loInclusive` / `hiInclusive`).

```kotlin
// 100 <= amount <= 500
Condition.Range(columnId = 3, lo = 100.0, hi = 500.0)

// amount >= 100 (open-ended)
Condition.Range(columnId = 3, lo = 100.0)

// amount < 500 (exclusive upper bound)
Condition.Range(columnId = 3, hi = 500.0, hiInclusive = false)
```

### `BitmapEq` - equality on a bitmap-indexed column

Best for low-cardinality columns (status, category, booleans).

```kotlin
Condition.BitmapEq(columnId = 2, value = "Alice")
```

### `IsNull` / `IsNotNull` - null checks

```kotlin
Condition.IsNull(columnId = 3)
Condition.IsNotNull(columnId = 3)
```

### `FmContains` - full-text substring search (FM-index)

Substring match within a column. The `pattern` becomes the on-wire search term.

```kotlin
val result = db.query(
    table = "documents",
    conditions = listOf(Condition.FmContains(columnId = 2, pattern = "database performance")),
    limit = 10,
)
```

For vector similarity (`ann`), sparse match, and MinHash similarity, use SQL
or extend the condition kinds - the server supports them on the wire; this
client covers the most common index conditions. See [sql.md](sql.md) for the
ones not yet exposed as native helpers.

## Projection (column selection)

Pass a `projection` list to restrict the columns in each returned row. Pass an
empty list for all columns. Projecting to only the columns you need cuts
bandwidth and decode cost.

```kotlin
val result = db.query(
    table = "orders",
    conditions = listOf(Condition.BitmapEq(columnId = 2, value = "Alice")),
    projection = listOf(1L, 2L),  // id and customer only
    limit = 100,
)
```

## Limit and the truncated flag

A non-zero `limit` caps the result. When the server has more matches than the
limit allows, it returns the first `limit` and sets `truncated` to `true`.

```kotlin
val result = db.query(table = "orders", conditions = conds, limit = 100)
if (result.truncated) {
    // 100 rows came back but more exist on the server. Either raise the
    // limit, page with a range predicate on the PK, or accept the cap.
}
```

## Multiple AND conditions

Pass a list of conditions. Every condition must match; the server intersects
the index results.

```kotlin
// Customer is Alice AND amount is between 100 and 500.
val result = db.query(
    table = "orders",
    conditions = listOf(
        Condition.BitmapEq(columnId = 2, value = "Alice"),
        Condition.Range(columnId = 3, lo = 100.0, hi = 500.0),
    ),
    projection = listOf(1L, 3L),
    limit = 50,
)
```

Because each condition targets a different specialized index, the engine can
pick the most selective one to drive the lookup and intersect the rest.

## Putting it together

A realistic combined lookup - bitmap equality + range + projection + limit +
truncation check:

```kotlin
fun topSpenders(db: MongrelDB, customer: String) {
    val result = db.query(
        table = "orders",
        conditions = listOf(
            Condition.BitmapEq(columnId = 2, value = customer),
            Condition.Range(columnId = 3, lo = 100.0),
        ),
        projection = listOf(1L, 3L),
        limit = 50,
    )
    if (result.truncated) {
        System.err.println("warning: result capped at 50")
    }
    for (row in result.rows) {
        println("id=${row.getLong(1)}, amount=${row.cells.firstOrNull { it.columnId == 3L }?.value}")
    }
}
```

For arbitrary predicates, joins, and aggregations that the native indexes do
not cover, use SQL instead - see [sql.md](sql.md).
