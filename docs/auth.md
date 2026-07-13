# Authentication & Authorization

A `mongreldb-server` daemon runs in one of three modes:

1. **Open** (default) - no auth required.
2. **Bearer token** (`--auth-token <TOKEN>`) - every request must carry an
   `Authorization: Bearer <TOKEN>` header.
3. **HTTP Basic** (`--auth-users`) - every request must carry an
   `Authorization: Basic <base64(user:pass)>` header.

The Kotlin/Native client supports all three through the `withToken` and
`withBasicAuth` factories. This guide shows each mode and how to manage users
and roles via SQL when the server is in Basic mode.

---

## Bearer token mode

Start the daemon with a token:

```sh
mongreldb-server --auth-token s3cret-token
```

Connect with `withToken`. The token is sent as `Authorization: Bearer ...` on
every request.

```kotlin
val db = MongrelDB().withToken("s3cret-token")

try {
    db.health()
} catch (e: AuthException) {
    System.err.println("bad or missing token: ${e.message}")
    return
}
```

A missing or wrong token surfaces as `AuthException` (HTTP 401/403).

### Where the token comes from

Hard-coding secrets in source is bad practice. Read it from the environment:

```kotlin
val token = processEnv("MONGRELDB_TOKEN")
    ?: error("MONGRELDB_TOKEN not set")
val db = MongrelDB().withToken(token)
```

(`Kotlin/Native exposes `platform.posix.getenv`; the live test suite shows a
small `getenv` helper you can copy.)

## Basic auth mode

Start the daemon with a users file or inline users:

```sh
mongreldb-server --auth-users
```

Connect with `withBasicAuth`:

```kotlin
val db = MongrelDB().withBasicAuth("admin", "s3cret")
```

The client base64-encodes `username:password` (with an inline encoder, since
Kotlin/Native has no `java.util.Base64`) and sets `Authorization: Basic ...`
on every request.

## Token takes precedence

A token takes precedence over basic auth. The two factories build separate
clients, so in practice you pick one, but the rule holds if you ever layer
them.

## Timeouts

The full constructor takes a `timeoutSeconds` argument (default 30). This
applies to both the connect and transfer phases on the underlying libcurl
engine.

```kotlin
val db = MongrelDB(
    url = "http://127.0.0.1:8453",
    authHeader = "Authorization" to "Bearer $token",
    timeoutSeconds = 60L,
)
```

## User and role management via SQL

When the daemon is in Basic auth mode, users and roles live in the catalog and
are managed with SQL. Run these statements through `sql`.

### Create a user

```kotlin
db.sql("CREATE USER alice WITH PASSWORD 'hunter2'")
```

### Alter a user

Change a password:

```kotlin
db.sql("ALTER USER alice WITH PASSWORD 'new-password'")
```

Grant the admin role:

```kotlin
db.sql("ALTER USER alice SET ADMIN TRUE")
```

`ALTER USER ... SET ADMIN TRUE` is how you promote a user to full
administrative privileges (table creation/drop, compaction, user management).
Use it sparingly.

### Drop a user

```kotlin
db.sql("DROP USER alice")
```

### Roles and grants

```kotlin
db.sql("CREATE ROLE analyst")
db.sql("GRANT SELECT ON orders TO analyst")
db.sql("GRANT analyst TO alice")
db.sql("REVOKE SELECT ON orders FROM analyst")
db.sql("DROP ROLE analyst")
```

Exact grant syntax mirrors the server's SQL flavor; consult the server's SQL
reference for the full `GRANT`/`REVOKE` grammar available in your build.

## Common pitfalls

**Auth errors look like other errors without the type.** A 401/403 maps to
`AuthException`; a 404 maps to `NotFoundException`. Always catch the typed
exception rather than string-matching `message`.

**Forgetting to set auth in production.** A client built with `MongrelDB()` and
no auth sends no credentials. Against an auth-enabled daemon, every call throws
`AuthException`. Centralize client construction so the auth factory is never
accidentally dropped.

**One client is one identity.** A `MongrelDB` client carries one set of
credentials. If you serve multiple authenticated users, build a client per user
with that user's token, and do not share it across threads (a client is not
thread-safe).

**Token in version control.** Put secrets in the environment, a secret
manager, or a file outside the repo. Never commit a real token.

## Next steps

- [errors.md](errors.md) - `AuthException` and the rest of the exception hierarchy
- [quickstart.md](quickstart.md) - the full end-to-end walkthrough
