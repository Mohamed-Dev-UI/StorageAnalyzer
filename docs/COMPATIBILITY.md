# Compatibility

Supported environments and engines. Everything marked **tested** is exercised
by this repository's build; anything outside the matrix is not covered by the
test suite.

## Release status

`0.1.0` is the first release, cut from the `v0.1.0` tag. The compatibility
matrix describes the source tree that produced it. API is pre-1.0: minor
releases may contain breaking changes. See [RELEASE.md](RELEASE.md) for the
publication process.

## JVM

| Environment | Status | Notes |
|---|---|---|
| JDK 17 | **Tested** | All published modules use `jvmToolchain(17)` and target Java 17 bytecode; the publish workflow uses Temurin 17. |
| JDK 21+ | Expected to work | No JDK-17-incompatible APIs are used; not asserted by the current suite. |
| Android API 28+ | **Supported** through `:app` | `:core`, `:filesystem`, `:history` and `:reactive` run in the showcase app (`minSdk 28`, tested on an API 36 emulator). `:sqlite` and `:room` require a JDBC driver and are JVM-only. |

## Kotlin

| Kotlin | Status | Notes |
|---|---|---|
| 2.2.10 | **Tested** | Compiler version used by the build. |
| Newer 2.x | Expected to work | The libraries publish Kotlin 2.2 metadata; consumers on newer compilers can read it. Not continuously tested. |

Kotlin code must target JVM 17. Java consumers can use the published
artifacts; the API is idiomatic Kotlin (default arguments, sealed types and
inline value helpers are visible as normal JVM signatures).

## Build toolchain (this repository)

| Component | Version |
|---|---|
| Gradle wrapper | 9.6.0 |
| Kotlin | 2.2.10 |
| Android Gradle Plugin | 9.4.1 |
| compileSdk / targetSdk / minSdk (`:app`) | 37 / 37 / 28 |
| kotlinx-coroutines | 1.10.2 |
| kotlinx-serialization-json (internal to `:room`) | 1.9.0 |
| org.xerial:sqlite-jdbc | 3.41.2.2 |
| androidx Room + KSP (showcase app) | 2.8.5 / 2.3.6 |

Consumers do not need the Android toolchain unless they build `:app`; the
library modules are plain Kotlin/JVM projects.

## Engines and platforms

| Module | Backend | Tested environment |
|---|---|---|
| `:filesystem` | `java.nio`, no native code | Windows 11 + NTFS; POSIX (Linux/macOS) expected but not asserted in this repository. Android host adapters (`allocationUnitResolver`, `volumeStatsResolver`) tested in `:app`. |
| `:sqlite` | JDBC + `org.xerial:sqlite-jdbc` 3.41.2.2 (SQLite 3.41.2) | Windows 11; read-only. `dbstat` support is detected at runtime. |
| `:history` | Versioned text snapshots | Pure JVM. |
| `:reactive` | kotlinx-coroutines 1.10.2 | Virtual-time and real dispatchers. |
| `:room` | Room schema-bundle JSON + `:sqlite` | Room-style WAL database fixtures; bundle parser tested against Room's exported shape. |
| `:app` | Android + Hilt + androidx Room/KSP + Compose | API 36 emulator (`Pixel_9_Pro_XL`), `minSdk 28`. |

## Filesystem feature matrix

| Feature | Windows (NTFS) | POSIX (Linux/macOS) |
|---|---|---|
| Logical size, streaming discovery | Tested | Expected |
| Hard-link dedup | Tested (`fileKey`) | Requires `fileKey`; otherwise paths count separately |
| Symlink detection | Tested (creation may need privileges) | Expected |
| Allocated size | Host resolver only (tested) | Host resolver only |
| Volume capacity | `FileStore` tested; host resolver tested | `FileStore` expected; host resolver supported |
| Reserved capacity | Host resolver only | Host resolver only |
| Sparse-file detection | Heuristic only | Heuristic only |
| Extended attributes | Unavailable | Unavailable |

See [LIMITATIONS.md](LIMITATIONS.md#filesystem-provider-filesystem) for the
reason behind each gap.

## SQLite feature matrix

| Feature | Behavior |
|---|---|
| Page capacity and freelist | Exact via PRAGMAs |
| Table/index storage | Exact via dbstat when the driver build provides it; otherwise `UNAVAILABLE` with a reason |
| Per-record sizes | `CONVENTION` using the SQLite record-format rules |
| Consistency health | `PRAGMA integrity_check`, read-only |
| WAL sidecars | Sized while a holder connection is open |
| WITHOUT ROWID / multi-column keys | Supported through pk-addressed identities |
| Views | Schema objects only, not data containers |
| Android | Not supported (JDBC driver required) |

## History format

| Component | Stability |
|---|---|
| `SnapshotFormat v1` | Versioned text header, strict decode; unknown versions fail loudly |
| `FileSnapshotStore` | One file per snapshot, append-only, locked appends |
| `InMemorySnapshotStore` | Test/demo scale only |

## Provider contracts and binary compatibility

The project is pre-1.0. The following policy applies until 1.0:

- Public API may change in a **minor** release when necessary; every change is
  recorded in [CHANGELOG.md](../CHANGELOG.md) with a migration note.
- New **default** methods may be added to provider SPIs in a minor release;
  existing implementations remain source-compatible.
- Built-in `MetricType` and `MeasurementMethod` ids are frozen. Custom ids must
  use the `x_<provider>.<name>` namespace.
- Published snapshot formats are versioned; readers must never silently accept
  an unknown format version.

After 1.0, normal SemVer applies: breaking changes require a major version.
