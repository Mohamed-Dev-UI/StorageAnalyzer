# Storage Analyzer

Honest, technology-independent storage analytics for the JVM.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-17%2B-informational.svg)](docs/COMPATIBILITY.md)
[![Status](https://img.shields.io/badge/status-0.1.0%20pre--release-orange.svg)](#project-status)

Storage Analyzer answers how storage is organized and consumed: what is stored
where, which resources share physical bytes, how confident each measurement
is, and how usage evolves over time. It is a normal Kotlin/JVM library with no
required database, ORM or DI framework — storage technologies plug in through a
small provider SPI.

Every value carries **provenance** (method, accuracy, backend, timestamp).
Anything that cannot be measured is reported as `UNAVAILABLE` with a reason —
never as a silent zero. Totals are deduplicated globally, so a SQLite file and
its database contents are never counted twice.

## Project status

**`0.1.0` is a pre-release development version. No Maven Central artifacts
exist yet.**

- The first public release is cut by pushing a `v0.1.0` tag (see
  [docs/RELEASE.md](docs/RELEASE.md)).
- Until then, consume the current source through a
  [composite build](#use-the-current-source-today) or run
  `./gradlew publishToMavenLocal` once and use `mavenLocal()`.
- The API is pre-1.0: minor releases may contain documented breaking changes.
  See [CHANGELOG.md](CHANGELOG.md).

## What it answers

| Question | Capability |
|---|---|
| What is eating the user's disk? | Streaming filesystem inventory, category share, largest files. |
| What is inside a SQLite database? | Schema-, page- and record-level analytics: tables, indexes, freelist, WAL sidecars. |
| Which rows or columns make a database grow? | Per-row encoded sizes and per-column aggregate breakdowns. |
| Where is space used across technologies? | Global deduplication: file-backed providers collapse aliases so bytes appear once. |
| How much storage does feature X or user Y use? | Entity descriptors, relation walking and five accounting views. |
| How did usage change, and where is it heading? | Signed snapshot comparisons plus ordinary-least-squares growth fitting. |
| What does a Room database use? | Room schema-bundle analysis: per-entity bytes, row counts, sidecars and drift diagnostics. |
| Can a UI update as storage changes? | Hot `StateFlow` reruns with digest-based change suppression. |
| Can this run on a large tree or slow device? | Time/resource/depth budgets with honest `PARTIAL` results and cancellation. |

## Design pillars

1. **Measure, don't guess.** Every number has a method, accuracy class and
   backend. Gaps are explicit.
2. **Count every byte exactly once.** Shared, overlapping and aliased resources
   are attributed by a documented accounting model.
3. **Read-only by construction.** Providers never write to analyzed storage.
4. **Streams, not series.** Discovery and record traversal are lazy and
   budget-bounded.
5. **Boring dependencies.** `:core` depends only on Kotlin/JVM; SQLite needs
   one JDBC driver; persistence uses versioned text.

## Requirements

- **JDK 17+** — all published modules use a Java 17 toolchain and bytecode.
- **SQLite provider / Room integration** — `org.xerial:sqlite-jdbc` at runtime,
  read-only, on any platform the driver supports.
- **Filesystem provider** — any `java.nio` filesystem; no native code.
- **Android** — the showcase app uses `minSdk 28`, `compileSdk`/`targetSdk` 37
  and is tested on an API 36 emulator. `:core`, `:filesystem`, `:history` and
  `:reactive` are Android-safe; `:sqlite` and `:room` are JVM/JDBC-only and are
  not shipped in the APK.

See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for the tested matrix.

## Install

### Maven Central (after the first release)

Artifacts are published as
`io.github.mohamed-dev-ui:storageanalyzer-<module>`. `mavenCentral()` is a
default Gradle repository, so no extra `repositories` block is needed.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.mohamed-dev-ui:storageanalyzer-filesystem:0.1.0")
    implementation("io.github.mohamed-dev-ui:storageanalyzer-sqlite:0.1.0")
    // optional:
    // implementation("io.github.mohamed-dev-ui:storageanalyzer-history:0.1.0")
    // implementation("io.github.mohamed-dev-ui:storageanalyzer-reactive:0.1.0")
    // implementation("io.github.mohamed-dev-ui:storageanalyzer-room:0.1.0")
}
```

Version catalog (recommended for multi-module builds):

```toml
# gradle/libs.versions.toml
[versions]
storageanalyzer = "0.1.0"

[libraries]
storageanalyzer-filesystem = { module = "io.github.mohamed-dev-ui:storageanalyzer-filesystem", version.ref = "storageanalyzer" }
storageanalyzer-sqlite     = { module = "io.github.mohamed-dev-ui:storageanalyzer-sqlite",     version.ref = "storageanalyzer" }
storageanalyzer-history    = { module = "io.github.mohamed-dev-ui:storageanalyzer-history",    version.ref = "storageanalyzer" }
storageanalyzer-reactive   = { module = "io.github.mohamed-dev-ui:storageanalyzer-reactive",   version.ref = "storageanalyzer" }
storageanalyzer-room       = { module = "io.github.mohamed-dev-ui:storageanalyzer-room",       version.ref = "storageanalyzer" }
```

Wait for `0.1.0` to appear on Maven Central after the first tag; the
[release process](docs/RELEASE.md) documents the timing.

### Use the current source today

Composite build — substitute published coordinates with the local modules:

```kotlin
// settings.gradle.kts of your project
includeBuild("../StorageAnalyzer") {
    dependencySubstitution {
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-filesystem")).using(project(":filesystem"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-sqlite")).using(project(":sqlite"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-history")).using(project(":history"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-reactive")).using(project(":reactive"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-room")).using(project(":room"))
    }
}
```

Or publish locally once and add `mavenLocal()` to your repositories:

```bash
./gradlew publishToMavenLocal
```

Full import options and module selection:
[docs/USAGE.md](docs/USAGE.md#2-consuming-the-library).

## Quickstart

Register providers, run one global analysis, read one honest number:

```kotlin
import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.measurement.StorageUnits
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider

fun main() {
    val analyzer = StorageAnalyzer(
        providers = listOf(
            // Filesystem first: registration order defines the owner of physical
            // identity, so the .sqlite file is counted once with its DB contents.
            FilesystemProvider(roots = listOf("/data"), allocationUnitResolver = { 4096L }),
            SQLiteStorageProvider(listOf(SQLiteDatabaseSpec("/data/app.sqlite"))),
        ),
    )

    analyzer.use {
        val report = it.analyzeGlobal()
        println(
            "unique footprint: " +
                StorageUnits.formatIec(report.totals.uniqueFootprint) +
                " (" + report.completeness + ", " + report.resourceCount + " resources)",
        )
        report.unmeasuredResources.forEach { println("not measured: $it") }
    }
}
```

`allocationUnitResolver` is optional; without it, allocated size is reported
as `UNAVAILABLE` instead of guessed. On Android, also bridge volume capacity
through `volumeStatsResolver` (see [USAGE.md](docs/USAGE.md) Scenario A).

Deeper scenarios — record-level database analytics, cross-provider dedup,
snapshots and forecasts, entity accounting, budgets, diagnostics, custom
providers, Room and live monitoring — are in the
[usage guide](docs/USAGE.md).

## Modules

All published modules target JVM 17. `:core` and `:database-api` arrive
transitively with the providers.

| Module | Depends on | Provides |
|---|---|---|
| `:core` | Kotlin/JVM only | Measurement model, identity, provider SPI, accounting, entity engine, snapshots, `StorageAnalyzer` facade. |
| `:database-api` | `:core` | Provider-agnostic relational contracts (`DatabaseIntrospector`, table/index/page descriptors). |
| `:filesystem` | `:core` | Read-only `java.nio` provider with hard-link dedup, volume metrics and host adapters. |
| `:sqlite` | `:core`, `:database-api`, sqlite-jdbc | Read-only SQLite analytics over JDBC. |
| `:history` | `:core` | Snapshot stores, comparisons and growth fitting. |
| `:reactive` | `:core`, kotlinx-coroutines | Hot/cold monitoring with digest-based change suppression. |
| `:room` | `:core`, `:sqlite`, `:reactive`, `:filesystem` (internal) | Room schema-bundle analysis, cold one-shot and hot monitor, zero androidx dependencies. |
| `:testing` | `:core`, kotlin-test/JUnit | `RecordingContext`, `TempDir`, `testIdentity` for provider tests. |
| `:example` | all providers | Runnable JVM demo and executable documentation scenarios (not published). |
| `:app` | `:core`, `:filesystem`, `:history`, `:reactive` + Android stack | Android showcase (not published). |

Roadmap modules that are documented but not shipped: KSP descriptor codegen,
cloud object-storage providers and official extra wire formats. See
[docs/LIMITATIONS.md](docs/LIMITATIONS.md#deferred-modules).

## Android showcase app

`:app` is a complete, modern reference consumer: **Hilt** DI, **androidx Room +
KSP** with exported schemas, **Compose Material 3**, lifecycle-aware `StateFlow`
rendering and a clean repository/ViewModel boundary. It covers the
Android-runnable library surface:

- **Overview** — deduplicated footprint, provider/category totals, volume
  capacity via `StatFs` (`volumeStatsResolver`), live monitor.
- **Explorer / resource detail** — inventory search, per-resource metrics and
  provenance, including honest `UNAVAILABLE` states.
- **History** — Room-persisted snapshots, comparisons and forecast.
- **Database** — Room schema notes and exported schema bundle.
- **Lab** — budgets and `PARTIAL` results, cancellation, diagnostics,
  capabilities, custom provider SPI.
- **Guide** — in-app implementation guide with recommended patterns.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # install on a running emulator/device
./gradlew :app:connectedDebugAndroidTest
```

Because `:sqlite` and `:room` require a JDBC SQLite driver, they are exercised
by JVM tests (`:app` unit tests and `:example`) rather than embedded in the
APK. The app demonstrates the JVM-safe modules, the host-adapter pattern and
the provider SPI.

The JVM demo runs every documented scenario:

```bash
./gradlew :example:run
```

## Documentation

Start at the [documentation index](docs/README.md).

| Document | Contents |
|---|---|
| [Usage guide](docs/USAGE.md) | Scenario cookbook A–L, import modes, error model, troubleshooting, FAQ. |
| [API reference](docs/API.md) | Facade, model types, providers, history, reactive, Room, testing, SPIs. |
| [Architecture](docs/ARCHITECTURE.md) | Module graph, dependency rules, data flow, identity schemes, extension points. |
| [Measurement accuracy](docs/MEASUREMENT_ACCURACY.md) | Accuracy ladder, never-zero guarantee and per-provider matrices. |
| [Accounting](docs/ACCOUNTING.md) | Unique-footprint semantics, deduplication, attribution, reconciliation. |
| [Compatibility](docs/COMPATIBILITY.md) | JVM/Kotlin/toolchain matrix and platform support. |
| [Limitations](docs/LIMITATIONS.md) | Known per-module limits and deferred modules. |
| [Performance](docs/PERFORMANCE.md) | Measured benchmarks, reproduction command and caveats. |
| [Release process](docs/RELEASE.md) | Versioning, Maven Central setup and release checklist. |
| [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md) | Repository policies. |

## Build and test

```bash
./gradlew build                                  # all JVM modules, tests and :app
./gradlew :example:run                           # runnable JVM demo
./gradlew :example:test -PwithBenchmarks=true    # manual benchmark probe
./gradlew :app:connectedDebugAndroidTest         # instrumented smoke test (device/emulator required)
```

The current suite executes **134 JVM tests** (39 core, 15 sqlite, 15
filesystem, 14 history, 8 reactive, 9 room, 15 example, 19 app unit tests),
plus **1 Android instrumented smoke test**. Three benchmark probes in
`:example` are skipped unless `-PwithBenchmarks=true` is set.

Documentation is part of the build: scenarios A–G and J–L in
[docs/USAGE.md](docs/USAGE.md) run through `UsageExamplesTest`, so stale
snippets fail CI.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues should be reported
privately per [SECURITY.md](SECURITY.md).

## License

MIT — see [LICENSE](LICENSE). The license, developer and SCM metadata are
declared in every published POM. Releases are published to Maven Central from
GitHub Actions; see [docs/RELEASE.md](docs/RELEASE.md).
