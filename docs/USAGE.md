# Usage Guide

Practical, scenario-driven guide for consuming the Storage Analyzer library.
The executable scenarios (A–G and J–L) are *continuously validated*: the
example module
(`example/src/test/kotlin/com/mohamed/storageanalyzer/example/UsageExamplesTest.kt`)
runs them against real fixtures as part of the regular test suite, so those
snippets cannot silently rot. Scenarios H (privacy/filtering) and I (health
checks) are covered by unit tests in `:core`, `:filesystem` and `:sqlite`.

For the type-level map of the public API, see [API.md](API.md). For runnable
demos, see the JVM [example](../example) and the Android
[showcase app](../app).

> TL;DR — register providers → call `analyzer.analyzeGlobal()` → print or
> store the report. Providers answer every metric honestly
> (`MEASURED` / `UNAVAILABLE` / `NOT_APPLICABLE` with provenance); global
> totals never double count shared or overlapping resources.

---

## Table of contents

1. [Concepts you need (2 minutes)](#1-concepts-you-need)
2. [Consuming the library](#2-consuming-the-library)
3. [Scenario A — *What is eating the user's disk?* (filesystem scan)](#scenario-a--what-is-eating-the-users-disk)
4. [Scenario B — *Inside my SQLite database* (schema-level)](#scenario-b--inside-my-sqlite-database)
5. [Scenario C — *Heaviest tables, rows and columns* (record-level)](#scenario-c--heaviest-tables-rows-and-columns-record-level)
6. [Scenario D — *Everything at once, no double counting* (composite device view)](#scenario-d--everything-at-once-no-double-counting)
7. [Scenario E — *Track storage growth over time* (history/forecast)](#scenario-e--track-storage-growth-over-time)
8. [Scenario F — *Account storage per application entity* (entity layer)](#scenario-f--account-storage-per-application-entity)
9. [Scenario G — *Responsive scans (UI/CI): budgets, cancellation, partials*](#scenario-g--responsive-scans-uici-budgets-cancellation-partials)
10. [Scenario H — *Privacy, exclusion filters, provider targeting*](#scenario-h--privacy-exclusion-filters-provider-targeting)
11. [Scenario I — *Health checks of stored data* (diagnostics)](#scenario-i--health-checks-of-stored-data)
12. [Scenario J — *Writing your own provider*](#scenario-j--writing-your-own-provider)
13. [Scenario K — *Room database investigation: cold + hot*](#scenario-k--room-database-investigation-cold--hot)
14. [Scenario L — *Live monitoring (hot flow)* (reactive)](#scenario-l--live-monitoring-hot-flow)
15. [Performance playbook (the fast paths)](#15-performance-playbook)
16. [Error model](#16-error-model)
17. [Troubleshooting](#17-troubleshooting)
18. [FAQ](#18-faq)

---

## 1. Concepts you need

| Concept            | What it is                                                                 | Where you see it           |
|--------------------|----------------------------------------------------------------------------|----------------------------|
| `StorageProvider`  | Adapter for one storage family (filesystem, SQLite, your own). Implements discovery + measurement of resources. | `StorageAnalyzer(providers = …)` |
| `StorageResource`  | One discovered storage unit (file, directory, db, table, index, row, …) with identity, kind, category and optional `decomposes` (see below). | discovery / report items   |
| `MetricType`       | *What* is measured: `stored_content_size`, `file_allocated_size`, `database_page_capacity`, … Each belongs to a dimension (`STORED`, `ALLOCATED`, `CAPACITY`, `FREE`, …) — different things are never conflated. | metric query sets          |
| `MeasurementState` | `MEASURED` / `UNAVAILABLE` (reason!) / `NOT_APPLICABLE`. There are **no silent zeros**: an unmeasured metric never appears as 0. | every `SingleMeasurement`  |
| `MeasurementProvenance` | *How* it was measured: method id, accuracy class (`EXACT`/`CONVENTION`/…), timestamp, backend version. | every measurement          |
| `ByteCount`        | The only unit: plain non-negative bytes, checked arithmetic. Deltas are signed longs. | everywhere                 |
| `decomposes`       | "These bytes already belong to another resource" — the mechanism that keeps hard links / aliased db files out of *unique* totals while staying visible. | `StorageResource`          |
| Room integration   | `:room` derives entity descriptors from Room's *exported schema bundle* (no androidx deps) and analyzes db + sidecars with drift diagnostics against the bundle. | Scenario K                 |
| Hot monitoring     | `:reactive` wraps analyses in a rerun loop publishing a `StateFlow` where identical content is suppressed via the `digest` change key; failures keep the last good value visible. | Scenario L                 |
| Snapshot           | Point-in-time summary (`StorageSnapshot`) of a full run — input for comparisons and forecasts. | history                    |

Two independent views exist and must never be mixed up:

- **Provider view** — what each provider sees (may include the same physical bytes twice).
- **Global view** — deduplicated, policy-attributed totals. *The `uniqueFootprint` in `GlobalReport` is the blessed "how much space is used" number.*

## 2. Consuming the library

The library is a normal multi-module Gradle/Kotlin project; everything
targets JVM 17 bytecode. Two supported import modes — **Maven Central**
(production) and a **composite build** (development against this repo).

### Option A — Import from Maven Central (after the first release)

> **Availability:** no Maven Central artifacts exist yet. `v0.1.0` will be the
> first published release; until then use Option B or
> `./gradlew publishToMavenLocal`.

No repository configuration is needed: `mavenCentral()` is one of Gradle's
default repositories.

**Step 1 — add the dependencies.** Coordinates are
`io.github.mohamed-dev-ui:storageanalyzer-<module>`; pick only what your
scenario needs (transitives handle everything else):

```kotlin
// your module's build.gradle.kts (Kotlin DSL)
dependencies {
    implementation("io.github.mohamed-dev-ui:storageanalyzer-filesystem")   // or :sqlite / :history / :core
    // live monitoring (hot StateFlow) — Scenario L:
    implementation("io.github.mohamed-dev-ui:storageanalyzer-reactive")
    // Room database integration — Scenario K:
    implementation("io.github.mohamed-dev-ui:storageanalyzer-room")
}
```

Groovy DSL equivalent:
`implementation "io.github.mohamed-dev-ui:storageanalyzer-filesystem"`.

**Step 2 — pin the version.** Add `:0.1.0` to each artifact name (releases
are cut by a `v*` tag push — see [RELEASE.md](RELEASE.md)):

```kotlin
implementation("io.github.mohamed-dev-ui:storageanalyzer-filesystem:0.1.0")
```

**Step 3 — (recommended, multi-module) pin once via a version catalog.**

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

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.storageanalyzer.filesystem)
    implementation(libs.storageanalyzer.room)   // pulls :core/:sqlite/:reactive transitively
}
```

**Step 4 — verify the import.**

```kotlin
import com.mohamed.storageanalyzer.core.StorageAnalyzer
// resolves → your classpath is correct
```

First-release timing: Central artifacts appear after the first `v*` tag push
([RELEASE.md](RELEASE.md)). Until then — or for local smoke tests — run
`./gradlew publishToMavenLocal` inside this repository and read the same
coordinates from `mavenLocal()` (unsigned).

### Option B — Gradle composite build (dev mode, this repo as source)

Every published artifact is substituted by its module, so your build always
compiles against the current sources. From your project:

```kotlin
// settings.gradle.kts of YOUR project
includeBuild("../StorageAnalyzer") {
    dependencySubstitution {
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-core")).using(project(":core"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-database-api")).using(project(":database-api"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-filesystem")).using(project(":filesystem"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-sqlite")).using(project(":sqlite"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-history")).using(project(":history"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-reactive")).using(project(":reactive"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-room")).using(project(":room"))
        substitute(module("io.github.mohamed-dev-ui:storageanalyzer-testing")).using(project(":testing"))
    }
}
```

Then declare the same
`implementation("io.github.mohamed-dev-ui:storageanalyzer-…")` dependencies
as in Option A — Gradle resolves them to this repository's modules.

**Option C — submodule include.** Clone this repository and add its module
folders directly to your `settings.gradle.kts` (`include(":core")`, … with
`projectDir = file("../StorageAnalyzer/core")`).

What to depend on for your goal:

| You want…                                    | Depend on                          |
|----------------------------------------------|------------------------------------|
| Files/folder analytics                       | `:filesystem`                      |
| SQLite analytics                             | `:sqlite` (pulls `:core`, `:database-api`) |
| Snapshots/comparisons/forecast               | `:history`                         |
| Only the pure model (custom providers)       | `:core`                            |
| Live monitoring (hot StateFlow / cold flow)  | `:reactive` (+ your provider modules) |
| Room database analytics, cold + hot          | `:room` (pulls `:core`, `:sqlite`, `:reactive`) |
| Test utilities (fakes, temp dirs)            | `:testing` (*testImplementation*)  |

In dependency strings these modules are published as
`io.github.mohamed-dev-ui:storageanalyzer-<module>` (e.g. `:filesystem` →
`io.github.mohamed-dev-ui:storageanalyzer-filesystem`); Java/Kotlin package
names remain `com.mohamed.storageanalyzer.*` regardless.

Room integration is **shipped** through `:room` (schema-bundle based, zero
androidx dependencies); KSP codegen and cloud-object-storage providers are on
the roadmap, not shipped — see `docs/LIMITATIONS.md`.

---

## Scenario A — *What is eating the user's disk?*

The most common question. One provider, one call:

```kotlin
import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.StorageUnits
import com.mohamed.storageanalyzer.filesystem.FilesystemProvider

fun diskUsage(rootDir: String): String {
    val analyzer = StorageAnalyzer(
        providers = listOf(
            FilesystemProvider(
                roots = listOf(rootDir),
                // OPTIONAL: your host knows the cluster size (Windows: 4096, e.g. via WMI)
                // → enables FILE_ALLOCATED_SIZE with CONVENTION accuracy.
                allocationUnitResolver = { _ -> 4096L },
                // OPTIONAL: supply volume numbers when java.nio can't resolve a
                // FileStore (Android scoped storage: bridge StatFs). Returning
                // null falls back to the JVM store.
                volumeStatsResolver = { path -> statFsVolumeStats(path) },
            ),
        ),
    )
    return analyzer.use {
        val report = it.analyzeGlobal(AnalysisOptions.DEFAULT)

        // The honest total: every unique file counted once.
        val headline =
            "unique footprint : " + StorageUnits.formatIec(report.totals.uniqueFootprint) +
                " (completeness: " + report.completeness + ", resources: " + report.resourceCount + ")"

        // Category share — what kind of content dominates?
        val categories = report.byCategory
            .sortedByDescending { b -> b[MetricType.STORED_CONTENT_SIZE]?.value ?: 0L }
            .take(5)
            .joinToString("\n") { b ->
                "  " + b.label.orEmpty().padEnd(12) +
                    (b[MetricType.STORED_CONTENT_SIZE]?.let { "::" + StorageUnits.formatIec(it) } ?: "")
            }

        // Top offenders — the largest files.
        val top = report.resourceSummaries
            .mapNotNull { s -> s.footPrintMetrics[MetricType.STORED_CONTENT_SIZE]?.let { sz -> s.identity.stableKey to sz } }
            .sortedByDescending { (_, sz) -> sz.value }
            .take(10)
            .joinToString("\n") { (key, sz) -> "  " + sz + "  " + key }

        // Did anything fail to measure? It is reported, never faked as 0.
        val unavailable = report.unmeasuredResources.take(5)
            .joinToString("\n") { u -> "  " + u.identity.stableKey + ": " + u.reason }

        headline + "\nby category:\n" + categories + "\nbiggest files:\n" + top +
            (if (unavailable.isNotEmpty()) "\nunmeasured:\n" + unavailable else "")
    }
}
```

Running inside an Android app or any long-lived service? Reuse one
`StorageAnalyzer` instance per scope and `close()` it when done (it closes
providers). Building providers is cheap; **discovery streams**, so you can
handle huge trees without loading them into memory (see Scenario G for
partial/budgeted scans).

### Variant: only a *fast* size of one folder (skip capacity probing)

Default options measure volume metrics too (nice for dashboards, slower on
extreme trees). For a pure "sum of file lengths" pass ask only for the logical
metrics:

```kotlin
val quick = analyzer.analyzeGlobal(
    AnalysisOptions(
        metrics = setOf(MetricType.STORED_CONTENT_SIZE),
        providerFilter = ProviderId("filesystem"),   // ⊙ narrow to one provider
    ),
)
println(StorageUnits.formatIec(quick.totals.uniqueFootprint))
```

## Scenario B — *Inside my SQLite database*

Tables, indexes, freelist and capacity of one (or many) databases — read-only,
no schema changes, honest when the bundle lacks dbstat:

```kotlin
import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import com.mohamed.storageanalyzer.sqlite.SQLiteStorageProvider

fun sqliteSummary(dbPath: String): String {
    val analyzer = StorageAnalyzer(
        providers = listOf(SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(dbPath)))),
    )
    return analyzer.use { a ->
        val report = a.analyzeGlobal()

        // db-level metrics straight from the run totals
        val pages = report.totals.byMetric[MetricType.DATABASE_PAGE_CAPACITY]?.value
        val freelist = report.totals.byMetric[MetricType.DATABASE_FREELIST_SIZE]?.value

        // tables and indexes are first-class resources — list them via discovery
        val p = a.providers().single() as SQLiteStorageProvider
        val ctx = AnalysisContext.create()
        val tables = p.discoverResources(null, ctx)
            .filter { it.kind == ResourceKind.TABLE }
            .mapNotNull { it.label }
            .toList()

        "completeness=" + report.completeness +
            " pageCapacity=" + pages + " freelist=" + freelist + " tables=" + tables
    }
}
```

Notes you will care about in production:

- **Locked DB / sidecar conventions**: WAL/`-journal`/`-shm` files are
  discovered and sized honestly (WAL is measurable while a holder connection
  is open).
- **Consistency**: `analyzer.runDiagnostics()` runs read-only
  `integrity_check` and reports `ERROR` diagnostics on corruption —
  cheap early-warning for a support button ("check my app's database").
- The db file itself is *not* double counted against the filesystem metrics
  when you also register a filesystem provider — see Scenario D.

## Scenario C — *Heaviest tables, rows and columns* (record-level)

Beyond schema: which rows and which columns consume the content?
Three verified entry points:

**(1) Stream every row of a table with its record-level size:**

```kotlin
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.sqlite.entityDescriptorForTable

fun heaviestRows(dbPath: String): List<Pair<String, Long>> {
    val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(dbPath)))
    val ctx = AnalysisContext.create()
    val systemId = p.systems().single()
    val users = p.entityDescriptorForTable("app.User", systemId, "users")!!

    val resolver = p.entityResolver()!!
    val out = ArrayList<Pair<String, Long>>()
    for (r in resolver.resourcesFor(users, ctx)) {
        val m = p.measure(r.identity, setOf(MetricType.DATABASE_RECORD_SIZE), ctx)
        m.valueOf(MetricType.DATABASE_RECORD_SIZE)?.let { out += r.label.orEmpty() to it.value }
    }
    p.close()
    return out.sortedByDescending { (_, v) -> v } // sort yourself: rows arrive streamed
}
```

Row sizes are **CONVENTION-accurate**: the exact serialized record
(header varints + serial types + payloads), explicitly excluding
cell/page overhead — check the provenance before you display a "(approx)"
marker in UI.

**(2) One row, with a per-column breakdown** (`recordBreakdown` is attached to
the measurement; conservation holds `Σfields + overhead == row size`):

```kotlin
val row = p.measure(recordIdentity, setOf(MetricType.DATABASE_RECORD_SIZE), ctx)
val breakdown = row.recordBreakdown!!
breakdown.fields.forEach { f ->
    println("column " + f.attributeName + " -> " + (f.value?.value ?: 0L) + " B [" + f.state + "]")
}
println("record header overhead : " + breakdown.overhead?.value)
```

**(3) Whole-table per-column aggregates** (which column eats the table?):

```kotlin
import com.mohamed.storageanalyzer.core.entity.spi.CollectionKey
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.StorageUnits

val engine = p.recordAnalytics()!!
val tableIdentity = p.discoverResources(null, ctx).first { it.kind == ResourceKind.TABLE && it.label == "users" }.identity
for (agg in engine.aggregateFieldBreakdown(CollectionKey(tableIdentity), ctx)) {
    println(agg.attributeName.padEnd(14) + " total=" + StorageUnits.formatIec(agg.totalAcrossRecords) +
        " populatedRows=" + agg.populatedRecords)
}
```

Where this shines: "why is my app's DB growing?" → biggest table → biggest
column (e.g. a bloated `payload` column) → actionable cleanup advice.

## Scenario D — *Everything at once, no double counting*

Composite view on a device/app storage: filesystem + SQLite in ONE report.
The browser/analyzer knows the db file both as a file (fs provider) and as a
database container (sqlite provider); registration order + declared aliases
collapse it automatically:

```kotlin
val analyzer = StorageAnalyzer(
    providers = listOf(
        FilesystemProvider(roots = listOf("/data")),               // FIRST: owns physical identity
        SQLiteStorageProvider(listOf(SQLiteDatabaseSpec("/data/app.sqlite"))),
    ),
)
val report = analyzer.analyzeGlobal()

report.overlapMeasurements.size  // >0? entries were collapsed — inside report.overlapMeasurements
report.totals.uniqueFootprint    // the honest both-worlds number: bytes counted once

// Explicit proof the accounting is conserved:
report.reconciliation.forEach { r ->
    println(r.checkId + ": " + r.leftLabel + "=" + r.leftBytes +
        ", " + r.rightLabel + "=" + r.rightBytes +
        (r.explanation?.let { e -> " — " + e } ?: ""))
}
```

Rules encoded in `InventoryComposer`:

1. First provider to discover a stable key wins; later duplicates are excluded (still reported).
2. A resource declaring `alsoDiscoverableBy` (e.g. the container file) is excluded when its alias was already seen.
3. Overlooks nothing: both provider *views* remain available in `report.providers` with their own totals.

## Scenario E — *Track storage growth over time*

Snapshots → append-only store → comparison → forecast. Works for sessions,
CI jobs, or a nightly worker:

```kotlin
import com.mohamed.storageanalyzer.history.FileSnapshotStore
import com.mohamed.storageanalyzer.history.GrowthModel
import com.mohamed.storageanalyzer.history.GrowthModelFactory
import com.mohamed.storageanalyzer.history.SnapshotComparison
import java.nio.file.Path

val store = FileSnapshotStore(Path.of("/data/analyzer/history")) // %012d.snapshot per append

// ... daily (cron, WorkManager, CI):
val snap = analyzer.captureSnapshot()      // one call, incl. per-resource detail for attribution
store.append(snap)

// ... whenever you want to explain change since the previous capture:
val all = store.readAll()
when (all.size) {
    0 -> println("nothing recorded yet")
    1 -> println("first snapshot: " + all[0].formatFootprint())
    else -> {
        val cmp = SnapshotComparison.between(all[all.size - 2], all.last())
        println("footprint " + (if (cmp.footprintDeltaBytes >= 0) "+" else "") + cmp.footprintDeltaBytes + " B")
        cmp.metricDeltas.forEach { (m, d) ->
            println(m.id.padEnd(24) + (if (d.deltaBytes >= 0) "+" else "") + d.deltaBytes + " B")
        }
        cmp.notComparableMetrics.forEach { (m, why) -> println("n/a  " + m.id + " -> " + why) }
        cmp.attribution?.let { attr ->
            println("created: " + attr.created.size + " removed: " + attr.removed.size +
                " changed: " + attr.changed.size + " unchanged: " + attr.unchangedCount)
            attr.topChanges(5).forEach { (key, bytes) -> println("  " + (if (bytes >= 0) "+" else "") + bytes + " B " + key) }
        }

        // Projection — ESTIMATED, never clamped, r² available:
        val model = GrowthModelFactory.fit(all)
        if (model is GrowthModel.Linear) {
            println("linear slope: " + model.slopeBytesPerSecond * 86_400.0 + " B/day, r²=" + model.r2)
        } else {
            println("no model: " + model.reason)
        }
    }
}
```

Guidelines: put the store next to app data; snapshots are small text files
(one per run); comparison with any two snapshots is instant. For retention,
`FileSnapshotStore.clear()` removes every stored file (delete oldest `N`
`.snapshot` files yourself for sliding windows).

## Scenario F — *Account storage per application entity*

When you want a product view ("how much is user #42's profile"), map entities
to resources — descriptors come with provider helpers for SQLite:

```kotlin
import com.mohamed.storageanalyzer.core.entity.EntityInstanceKey
import com.mohamed.storageanalyzer.sqlite.entityDescriptorForTable

val p = SQLiteStorageProvider(listOf(SQLiteDatabaseSpec(dbPath)))
val systemId = p.systems().single()
val descriptor = p.entityDescriptorForTable(
    entityTypeId = "app.User",
    systemId = systemId,
    tableName = "users",
    displayName = "User",
)

val analyzer = StorageAnalyzer(providers = listOf(p))
val one = analyzer.analyzeEntity(descriptor!!, EntityInstanceKey("42"))
println("direct   : " + one.direct[MetricType.DATABASE_RECORD_SIZE])
println("exclusive: " + one.exclusive[MetricType.DATABASE_RECORD_SIZE])
println("inclusive: " + one.inclusive[MetricType.DATABASE_RECORD_SIZE])

// Or aggregate over the whole type (every instance of the concept):
val all = analyzer.analyzeEntityType(descriptor)
println("attribute across all users: " + all.totalAttributedFootprint())
```

The five views (`direct`, `exclusive`, `inclusive`, `uniqueReachable`,
`sharedAllocated`) follow the documented entity semantics: inclusive sizes
traverse declared `relations` (bounded by budgets); sharing is attributed by
the policy, never double counted.

`WITHOUT ROWID` tables use `entityDescriptorForPkTable(..., pkColumnCount)`.
For composite keys join the pk components with `\u001F` in that order.

## Scenario G — *Responsive scans (UI/CI): budgets, cancellation, partials*

Scans are streams and every step contextualizes budgets. For a UI "Storage"
screen you rarely need everything:

```kotlin
import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import java.time.Duration

val report = analyzer.analyzeGlobal(
    AnalysisOptions(
        metrics = setOf(MetricType.STORED_CONTENT_SIZE),           // skip FileStore probing
        budget = AnalysisBudget(
            time = Duration.ofSeconds(5),        // deadline → results become PARTIAL
            maxResources = 50_000,               // cap discovery
            maxDepth = 6,                        // entity traversal depth
        ),
    ),
)
if (report.completeness == Completeness.PARTIAL) {
    // show honest state: "covered X of Y — stopped by budget"
}
```

Cancellation (user left the screen / job preempted):

```kotlin
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.StorageError
import kotlin.concurrent.thread

val context = AnalysisContext.create()
val job = thread(start = true) { analyzer.analyzeGlobal(AnalysisOptions.DEFAULT, context) }
// ... user pressed "stop":
context.cancel()   // cooperative stop; the run surfaces StorageError.Cancelled
```

All providers interleave `ctx.ensureActive()` and budget consumption, stop
promptly and report only what was covered (never partially measured rows
faked as whole). `AnalysisContext`/`BudgetedAnalysisContext` are the single
thread-cancellation contract — never wrap in your own locking.

Also on the fast path: your **metric query set** decides provider costs —
checked against `provider.capabilitiesFor(systemId)` when you need to know
what is possible before starting.

## Scenario H — *Privacy, exclusion filters, provider targeting*

```kotlin
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.resource.StorageCategory

val options = AnalysisOptions(
    redactLabels = true,                       // human labels → stable local hash
    excluded = { id -> id.nativeId.startsWith(".cache") }, // drop resources entirely
    categories = setOf(StorageCategory.MEDIA), // coarse focus, provider-filtered capacity
    providerFilter = ProviderId("filesystem"), // run ONE provider only
    includeIndexes = false,                    // schema-librarian mode
    includeFreeSpace = true,                   // dashboards: show free space too
)
val report = analyzer.analyzeGlobal(options)
```

Exclusions are honest: they are dropped from the inventory, never reported as
zero-byte. **Redaction caveat:** `redactLabels` currently applies to
human *labels* where they appear (e.g. overlap measurement labels) — resource
`stableKey`s are intentionally NOT redacted because dedup and growth
attribution depend on them; hash keys yourself if you publish fully
anonymized reports (see `docs/LIMITATIONS.md`).

## Scenario I — *Health checks of stored data*

```kotlin
val diagnostics = analyzer.runDiagnostics()
diagnostics.forEach { d ->
    println("[" + d.severity + "] " + d.code + " — " + d.message)
}
```

Shipped checks: SQLite `integrity_check` (ERROR on corruption), filesystem
root existence/health, registration sanity (`general.no_providers`,
descriptor/provider mismatches). All read-only, safe at app start.

## Scenario J — *Writing your own provider*

Minimal shape — this exact pattern compiles and runs in
`example/src/main/kotlin/com/mohamed/storageanalyzer/example/UsageExamples.kt`
(`ToyCloudProvider`, exercised by `UsageExamplesTest`):

```kotlin
class MyCloudProvider : StorageProvider {                      // AutoCloseable
    override val providerId = ProviderId("x_mycloud")          // x_<name> prefix
    override fun describe() = "my cloud (v1)"                  // provenance backend string

    override fun capabilities() = Capabilities(
        supported = setOf(StorageCapability.FILE_LENGTH),
        unsupportedReasons = mapOf(StorageCapability.VOLUME_SPACE_STATS to
            "quotas are not exposed by this backend"),
    )
    override fun capabilitiesFor(systemId: StorageSystemId) = capabilities()

    override fun discoverResources(systemId: StorageSystemId?, ctx: AnalysisContext) = sequence {
        ctx.ensureActive()
        for (obj in listObjects()) {                           // stream, never collect-all
            if (obj.name.startsWith(".hidden")) continue
            yield(StorageResource(                             // providerId/systemId are required
                identity = ResourceIdentity(
                    providerId = providerId,
                    systemId = StorageSystemId("bucket/my-bucket"),
                    kind = ResourceKind.FILE,
                    nativeId = canonical(obj.name),
                ),
                providerId = providerId,
                systemId = StorageSystemId("bucket/my-bucket"),
                label = obj.name,
                kind = ResourceKind.FILE,
                category = StorageCategory.OTHER,
            ))
        }
    }

    override fun measure(resource: ResourceIdentity, metrics: Set<MetricType>, ctx: AnalysisContext): StorageMeasurement {
        ctx.ensureActive()
        val now = Instant.now()
        return StorageMeasurement(                             // every metric gets an answer
            identity = resource,
            kind = resource.kind,
            category = StorageCategory.OTHER,
            measurements = metrics.associateWith { m ->
                when (m) {
                    MetricType.STORED_CONTENT_SIZE -> SingleMeasurement.measured(
                        m,
                        remoteSizeOf(resource),                // your backend call
                        MeasurementProvenance(
                            method = MeasurementMethod.custom("x_mycloud.head"),
                            accuracy = MeasurementAccuracy.EXACT,
                            measuredAt = now,
                            providerId = providerId.value,
                            backend = "my-cloud-sdk 1.0",
                        ),
                    )
                    else -> SingleMeasurement(                 // honest answer, never 0
                        metric = m,
                        value = null,
                        state = MeasurementState.UNAVAILABLE,
                        provenance = MeasurementProvenance.absent(
                            providerId = providerId.value,
                            measuredAt = now,
                            method = MeasurementMethod.custom("x_mycloud.head"),
                            reason = "this provider only measures object sizes",
                        ),
                        reason = "this provider only measures object sizes",
                    )
                }
            },
            completeness = Completeness.COMPLETE,
        )
    }

    override fun defaultMetricTypes() = setOf(MetricType.STORED_CONTENT_SIZE)
    override fun close() {}
}
val analyzer = StorageAnalyzer(providers = listOf(MyCloudProvider()))
// or later: analyzer.register(MyCloudProvider())
```

Honesty contract: can't measure → `UNAVAILABLE` + reason, never `0`;
two paths to the same physical object? declare `alsoDiscoverableBy`
aliases (Scenario D) to stay leak-free across provider registrations.

---

## Scenario K — *Room database investigation: cold + hot*

Everything a Room developer otherwise hand-wires — filesystem provider for
the db + sidecar files, SQLite provider, exclusion of unrelated files in the
same directory, one descriptor per `@Entity` — is built for you by
`RoomDatabaseAnalyzer`. Entity descriptors are derived from Room's **schema
bundle** JSON (`exportSchema = true`, enabled by default in every Room
project): zero androidx dependencies, no reflection on annotations (Room's
annotations are binary-retention and invisible to JVM reflection).

Verified fixture and snippet: `UsageExamples.buildRoomFixture` /
`UsageExamples.roomOverview`, exercised by `UsageExamplesTest` (scenario K).

```kotlin
val (db, bundle) = buildRoomFixture(root)                 // app.db + schemas/AppDatabase/1.json
val analyzer = RoomDatabaseAnalyzer(                      // wires fs + sqlite providers for you
    spec = SQLiteDatabaseSpec(db.canonicalPath),
    schemaBundle = RoomSchemaBundle.parse(bundle),
)
analyzer.use { ra ->
    val analysis = ra.analyzeOnce()                       // cold one-shot
    analysis.totals.uniqueFootprint                       // db + sidecars only — no junk files
    analysis.totals.dbFileBytes                           // main file (null = unmeasured, never 0)
    analysis.totals.pageCapacity                          // page_capacity / freelist (engine-only)
    analysis.entities                                     // per-entity: table+index bytes, row count
    analysis.sidecars                                     // -wal / -shm / rollback journal by role
    analysis.drift                                        // consistency against the bundle
}
// per-record detail is opt-in (heaviest path, still budget-capped):
// RoomAnalysisConfig(includeRowScan = true) -> rowsBytesSum / maxRowBytes
```

Drift diagnostics contract:

| Code | Severity | Meaning |
|---|---|---|
| `room.drift.identity_hash` | ERROR | `room_master_table` hash ≠ bundle hash — re-export the bundle |
| `room.drift.missing_table` | ERROR | bundled entity has no live table — migrations pending |
| `room.drift.extra_table` | WARNING | live table not in the bundle — stale export (still analyzed) |
| `room.drift.schema_version` | WARNING | `user_version` differs from the bundle version |
| `room.drift.master_missing` | INFO | no readable `room_master_table` (db not yet touched by Room) |

**Hot flow** for file-backed databases: `RoomStorageMonitor` fingerprints
the database cheaply (`PRAGMA data_version` / `user_version` / page count /
file length) every `pollInterval` and re-emits a fresh `RoomAnalysis` only
when content actually changed (digest-based):

```kotlin
val monitor = RoomStorageMonitor(
    spec = SQLiteDatabaseSpec(db.canonicalPath),
    schemaBundle = RoomSchemaBundle.parse(bundle),
    scope = applicationScope,               // caller-owned CoroutineScope
    pollInterval = Duration.ofSeconds(2),
).apply { start() }

monitor.results.collect { analysis -> render(analysis) }   // hot StateFlow<RoomAnalysis>

// Optional instant refresh for app-made Room writes:
roomDb.invalidationTracker.addObserver { monitor.refresh("room") }
// (remember removeObserver on close)
```

`:memory:` or non-file databases degrade to `RefreshPolicy.Manual` (there is
no native fingerprint to poll) — drive them with `refreshNow()` / `refresh()`.
A failing turn keeps the last good analysis visible
(`RerunState.Failed.previous`) and the loop retries on the next trigger.

Requires a SQLite JDBC driver on the runtime classpath (e.g.
`org.xerial:sqlite-jdbc`), exactly like the `:sqlite` provider itself.

---

## Scenario L — *Live monitoring (hot flow)*

Re-run *any* analyzer on a refresh policy and publish results only when
content actually changed — one wrapper over your `StorageAnalyzer`:

Verified snippet: `UsageExamples.reactiveManualSummary`, exercised by
`UsageExamplesTest` (scenario L, deterministic `RefreshPolicy.Manual` mode).

```kotlin
val reactive = ReactiveAnalyzer(
    analyzer = analyzerFor(root),           // your StorageAnalyzer
    scope = applicationScope,               // caller-owned CoroutineScope
    policy = RefreshPolicy.Every(Duration.ofSeconds(30)),
).apply { start() }

reactive.state.collect { state ->           // Idle → Running → Completed / Failed
    // render `state.previous` while a turn is running — stale data is explicit
}
reactive.results.value                      // latest report; unchanged content is suppressed
```

Semantics worth knowing:

- **Digest-based emission**: equal content never re-emits (digest =
  completeness + totals + per-resource measurements), so UIs keyed on the
  digest do not re-render for identical data.
- **Failures are state, not crashes**: a failed turn surfaces as
  `RerunState.Failed(error, previous)` and the loop retries next turn.
- **Cold path**: `analyzer.coldReportFlow(options)` — one fresh analysis per
  collection, no background state; `refreshNow()` runs a single turn through
  the hot pipeline as well.
- `close()` stops only the monitor's own jobs — your `StorageAnalyzer` and
  scope stay untouched.
- Refresh policies: `Every(interval)`, `OnChange(probe, pollInterval)` (cheap
  fingerprint, first turn runs immediately), `Manual` (you call it).

---

## 15. Performance playbook

| Need                                                         | Do this                                                                  | Why                                        |
|--------------------------------------------------------------|--------------------------------------------------------------------------|--------------------------------------------|
| Just sizes, fastest                                          | `AnalysisOptions(metrics = setOf(STORED_CONTENT_SIZE))`                  | skips FileStore/allocation probes          |
| Show allocated-on-disk sizes                                 | set `allocationUnitResolver` (jvm lacks a portable cluster size)          | turns `FILE_ALLOCATED_SIZE` from UNAVAILABLE to CONVENTION |
| Volume capacity on Android                                   | set `volumeStatsResolver` (bridge `StatFs`)                               | NIO cannot resolve a FileStore in scoped storage |
| UI responsive on huge trees                                  | `budget = AnalysisBudget(time = …, maxResources = …)`                    | honest partial results, quick return       |
| User leaves the screen                                       | `ctx.cancel()`                                                           | immediate cooperative stop                 |
| Can I afford growth on CI every commit?                     | append snapshot → compare with `SnapshotComparison` (3.9 ms @ 5,000 details) | O(snapshot size)                           |
| Rebuild dashboards repeatedly without re-scan               | keep last `GlobalReport`/snapshot and diff new runs                      | streaming discovery repeated is the cost   |
| Row-by-row db exploration                                    | stream via `entityResolver()` (rowid addressing)                         | ~134 µs/row measured; no page lookups       |
| Which column is the disk-hog                                 | `recordAnalytics().aggregateFieldBreakdown(CollectionKey(tableIdentity))` | one SQL pass over aggregates               |
| Room dashboard that stays live                               | `RoomStorageMonitor` with the fingerprint probe + `pollInterval`          | the probe (3 pragmas + file length) is O(1); full re-analysis only fires on real content change |
| Avoid Room re-analysis churn                                | keep `digest` as the render key and rely on emission suppression         | identical content never re-emits           |

Measured numbers (hardware of the day, for scale): see
[PERFORMANCE.md](PERFORMANCE.md).

## 16. Error model

- **Results are never wrong silently.** Missing measurements appear with
  `UNAVAILABLE` state + reason; unmeasured resources appear in
  `report.unmeasuredResources`; runs hit by budgets are `PARTIAL` with
  diagnostics.
- **Hard failures are typed**: `StorageError.Cancelled` (budget/cancel),
  `StorageError.ProviderUnavailable`, `StorageError.IdentityConflict`.
- **Corruption is loud**: a corrupt snapshot file raises `IOException`
  (nothing is skipped); a failing `integrity_check` becomes an ERROR
  diagnostic (report remains valid).
- Check numbers you display before trusting them: `report.unmeasuredResources`
  (what could not be measured at all), `measurement.valueOf(metric)`
  (null when not MEASURED), `measurement.missing()` (every gap with its
  reason), and the `provenance` of each value — over assuming MEASURED.
- **Room results are no exception**: `null` entity/totals entries mean "not
  measured this run", `drift` is diagnostic (not exception) state, and the
  hot monitor reports failures as `RerunState.Failed(error, previous)` while
  keeping the last good analysis visible.

## 17. Troubleshooting

| Symptom                                          | Cause / fix                                                                                       |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `FILE_ALLOCATED_SIZE` is UNAVAILABLE             | no cluster size on JVM: pass `allocationUnitResolver` or display the honest state                   |
| `TOTAL_CAPACITY` is UNAVAILABLE                  | no resolvable `FileStore` (common on Android): pass `volumeStatsResolver`, e.g. backed by `StatFs`  |
| My `.sqlite` analyzed twice (as file + as db)     | register the **filesystem provider first**, or the dedup alias chain is broken by custom config     |
| Record sizes look small                          | they exclude cell/page overhead by design (CONVENTION); page-level totals are the ground truth      |
| Everything seems PARTIAL                         | a budget or cancellation fired: read `report.diagnostics` and the `unmeasuredResources` reasons      |
| WAL measured but db looks bigger later           | WAL exists only while a connection is open — closing checkpoints; snapshot diff will reflect it     |
| Growth model `Unavailable`                       | fewer than 2 snapshots, or all at the same timestamp — capture then wait a real interval            |
| `MetricType` id conflicts                        | custom ids must be `x_<provider>.<name>` and lowercase; built-ins are frozen                        |
| Android build pulls xerial sqlite?               | don't depend on `:sqlite` (or `:room`) for an Android app module unless you accept the native sqlite footprint; `:core` + `:filesystem` are JVM-pure |
| `:room` monitor finds no changes / probe null    | fingerprinting needs a file-backed db + any SQLite JDBC driver on the classpath; for instant refresh hook `InvalidationTracker` → `monitor.refresh()` |

## 18. FAQ

**Why doesn't the library give me one "size" per item?**
There is no meaningful single size: logical vs allocated vs page-level vs
record-encoding are *different quantities* (different dimensions). Ask for
the metric you actually want and display the accuracy class next to it.

**Is it safe to run against my production database?**
The provider opens SQLite read-only (and additionally `PRAGMA query_only`).
`integrity_check` is read-only. The busy timeout is configurable per
database via `SQLiteDatabaseSpec.busyTimeoutMs` (default 5 s). The provider
never writes.

**Can I scan a whole phone?**
`FilesystemProvider` streams any tree rooted at your paths — compose roots,
deduplicate overlapping roots automatically by stable identity (tested).

**Where do I see *why* a number exists?**
`SingleMeasurement.provenance` → method id, accuracy class, timestamp,
backend string. Snapshots carry the same information to comparisons.

**How do I get Android Room per-table analytics?**
Shipped: use the `room` module (`RoomDatabaseAnalyzer` for a cold one-shot,
`RoomStorageMonitor` for the hot flow) — Scenario K. It derives entity
descriptors from Room's exported schema bundle and needs no androidx
dependency; rows/counters come from the SQLite level, which is where Room's
tables live.

**Does it work on Android?**
`:core` and `:filesystem` are pure Kotlin/JVM and run on API 28+; the
showcase app in `:app` binds the filesystem provider to app-private
directories and supplies `StatFs`-backed volume stats. `:sqlite` and `:room`
are JVM-only because they use a JDBC driver — see
[COMPATIBILITY.md](COMPATIBILITY.md).
