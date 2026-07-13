# Contributing to MongrelDB Kotlin/Native

Thanks for taking the time to help the MongrelDB Kotlin/Native client. This
document describes how to propose a change, what we expect from a pull request,
and the coding standards that apply to the codebase.

If anything here is unclear or out of date, open an issue or a PR.

## Code of conduct

Be kind, be specific, assume good faith. Disagree about the technical
details, not the person. Public reviews stay focused on the diff.

## How to propose a change

The MongrelDB Kotlin/Native client uses a standard **fork -> branch -> pull
request** workflow on GitHub.

1. **Fork**
   [`visorcraft/MongrelDB-Kotlin-Native`](https://github.com/visorcraft/MongrelDB-Kotlin-Native)
   to your GitHub account.
2. **Clone** your fork and add the upstream remote:

   ```sh
   git clone git@github.com:<you>/MongrelDB-Kotlin-Native.git
   cd MongrelDB-Kotlin-Native
   git remote add upstream https://github.com/visorcraft/MongrelDB-Kotlin-Native.git
   ```

3. **Branch** from `main`. Pick a descriptive, kebab-case branch name:
   `fix-query-decode`, `feature/vector-search`, `docs/auth-guide`.

   ```sh
   git fetch upstream
   git switch -c my-change upstream/main
   ```

4. **Make focused commits.** One logical change per commit. Run the
   preflight (see below) before pushing.
5. **Open a pull request** against `main` on
   `visorcraft/MongrelDB-Kotlin-Native`. Fill in the PR template:
   - **What.** One paragraph summary of the change.
   - **Why.** Bug fix? New feature? Doc fix? Link the issue if one
     exists.
   - **How to test.** The exact commands a reviewer should run.
   - **Risk.** What might break? What did you not test?

## Before you push: preflight

Run the full CI preflight locally:

```sh
./gradlew check
```

This compiles every native target (`linuxX64`, `macosX64`, `macosArm64`,
`mingwX64`), runs the unit and wire-shape tests, and produces the `.klib`
artifacts. The build must be warning-clean. If a check fails, fix the root
cause - don't suppress warnings or skip the test.

To run the live integration suite (requires a running `mongreldb-server`):

```sh
# Boot a local daemon and point the tests at it:
mkdir -p /tmp/mdb-data
./bin/mongreldb-server /tmp/mdb-data &
MONGRELDB_URL=http://127.0.0.1:8453 ./gradlew linuxX64Test
```

Live tests self-skip when no server is reachable (they throw
`TestSkippedException`, which the daemon-free CI path tolerates).

### Native-mode preflight

The native source set (`NativeDB.kt` + the `cinterop` bindings) is opt-in.
To compile and test it you need the prebuilt `libmongreldb` +
`libmongreldb_kit` archives on the linker path:

```sh
export MONGRELDB_NATIVE_DIR=$PWD/mongreldb-native/lib
./gradlew check -PenableNative=true
```

When `-PenableNative=true` is absent, `NativeDB.kt` is excluded from
compilation, so HTTP-only contributors need no native libraries.

## What we look for in a review

- The change does one thing and does it well.
- Behavior changes ship with tests. New client behavior: a unit test
  alongside the code. Wire-format changes: cover the exact outgoing JSON
  keys (see `CreateTableWireShapeTest`). Daemon-dependent coverage: a live
  test in `MongrelDBLiveTest` that skips cleanly when no server is
  available.
- The change keeps this repo a thin client over `mongreldb-server`. Don't
  re-implement storage, indexing, WAL, or SQL planning logic here. The
  native layer should stay a small, safe wrapper over the Kit C ABI.
- Documentation is updated alongside the code (`docs/`, `README.md`) if the
  change affects users.
- Commits have clear messages (see below).

## Coding standards

### Kotlin (KMP / Kotlin/Native)

- **Language version.** Kotlin 2.1.x (the version pinned by the Gradle
  plugin). Do not use unstable/experimental language features behind
  opt-in flags without justification.
- **Multiplatform layout.** HTTP client code lives in `commonMain` where
  possible; libcurl/ktor-curl specifics and `platform.posix`/`kotlinx.cinterop`
  calls live in `nativeMain`. The FFI surface for native embedding lives in
  `nativeMain` under the `-PenableNative=true` gate. Wire-shape and pure
  model tests go in `commonTest`; daemon-dependent tests go in `nativeTest`.
- **Warnings.** `./gradlew check` must be clean. Treat warnings as errors
  locally where practical.
- **Dependencies.** ktor-client-curl (HTTP transport) and
  kotlinx-serialization-json (wire format) are the runtime dependencies. Do
  not pull in an external HTTP or JSON library. New third-party
  dependencies must be MIT or Apache-2.0 licensed and justified.
- **Nullability and ownership.** Document ownership in KDoc. Native FFI
  handles returned from C must be freed through the matching `*_free` call
  inside a `memScoped` block; never leak a `CPointer` across scope.
- **Thread safety.** A `MongrelDB` client and a `NativeDB` handle are both
  NOT thread-safe (single-threaded). Document this and do not add locking.
  For `NativeDB` this is enforced by Kotlin/Native's memory model
  (`Rc<RefCell>`).
- **Errors.** Throw typed subclasses of `MongrelDBException` from public
  functions (`AuthException`, `NotFoundException`, `ConflictException`,
  `QueryException`). Map HTTP status to the right category in
  `HttpTransport.handleResponse`. Never swallow an error silently.
- **Serialization.** Wire types use `kotlinx.serialization`. Add
  `@SerialName` for snake_case wire keys; set `ignoreUnknownKeys = true` and
  `explicitNulls = false` on the `Json` instance so additive server changes
  do not break decoding.
- **Naming.** `PascalCase` for classes, interfaces, sealed types, and type
  parameters. `camelCase` for functions, properties, and locals. `SCREAMING_SNAKE_CASE`
  for constants (`DEFAULT_URL`). Package names are lowercase
  (`com.visorcraft.mongreldb`).
- **Style.** 4-space indent, no tabs, opening brace on the same line, one
  top-level declaration per file where practical. Sealed classes for closed
  hierarchies (`Value`, `Condition`). `data class` for value objects
  (`Cell`, `Row`, `Column`, `InputCell`). Run `ktlint`/`spotless` if you
  have it, but matching the surrounding style is what matters.

### Commit messages

- Subject line: imperative mood, <= 72 characters, no trailing period.
  Example: `Add FM-index full-text condition to query builder`.
- Body: wrap at 72 characters. Explain *why*, not *what* (the diff
  shows the what).
- Reference issues with `Fixes #123` / `Refs #123` on a final line
  when applicable.
- **Never** add AI/assistant attribution (no `Co-Authored-By`, no
  `Generated with`, no tool names).

## Issue reports

A useful bug report includes:

- The MongrelDB Kotlin/Native client version (from git tag or the Gradle
  `version` property in `build.gradle.kts`).
- Your Kotlin toolchain version (`kotlin -version` or the Gradle plugin
  version), Gradle version (`./gradlew --version`), and OS + target
  (`linuxX64`, `macosArm64`, `mingwX64`, ...).
- The `mongreldb-server` version if the issue involves live requests.
- The exact code or commands that reproduce the issue.
- The expected result and the actual result.
- Any stack trace or error output.

Feature requests are welcome. Please describe the problem you're trying
to solve before proposing the solution.

## Security

If you find a vulnerability, **do not** open a public GitHub issue.
Report it privately through GitHub's private vulnerability reporting -
the repository's **Security** tab -> **Report a vulnerability**. The full
policy is in [`SECURITY.md`](SECURITY.md).

## Licensing

The MongrelDB Kotlin/Native client is dual-licensed under MIT OR
Apache-2.0. Every source file carries `SPDX-License-Identifier: MIT OR
Apache-2.0` in its header. By contributing, you agree that your changes
are made available under the same license.

- Do **not** paste code from other database clients unless you have done
  a license review first.
- New third-party dependencies must be MIT or Apache-2.0 licensed.

Thanks again - looking forward to your PR.
