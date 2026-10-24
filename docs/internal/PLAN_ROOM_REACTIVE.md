# Plan: Room integration + reactive (hot/cold) analytics API

> **Internal record.** This is a historical design document, not part of the
> public documentation. Details may be stale; the shipped code and the public
> docs are authoritative.

Status: **P0 implemented** (see §9). This document is the design record that
keeps the added modules bus-factor-proof: why they exist, what they depend
on, what each public symbol means, and which limits are honest limitations
rather than accidents.

---

## 1. Goal

Two developer pain points this change removes:

1. **Room users analyze "the Room database" today by hand-wiring three
   things**: a filesystem provider for the db file and its sidecars, an
   SQLite provider, and `entityDescriptorForTable(...) for each @Entity`.
   That is ~40 lines of boilerplate per app, repeated everywhere.

2. **Live monitoring today is manual**: callers must re-run `analyzeGlobal`
   on a timer, diff reports themselves, and handle failures without ever
   letting the UI state become stale or lying.

### The repetitive code that disappears (before → after)

| Task today (manual)                                       | After this change                          |
|-----------------------------------------------------------|--------------------------------------------|
| Build fs + sqlite providers around the db file             | `RoomDatabaseAnalyzer(spec)` builds them     |
| Write one `entityDescriptorForTable` per Room entity        | auto-generated from the Room schema bundle   |
| Know WAL/-shm/-journal semantics and dedup rules (§11)      | provided; sidecars measured, none double counted |
| Timer + manual re-run + diff + stale handling for live UI   | `monitor.results` is a hot `StateFlow<RoomAnalysis>` updated when content changes |
| One-shot snapshot code (cold path)                          | `analyzeOnce()` returns a complete single-shot result |
| Track `room_master_table` / `user_version` drift yourself    | drift diagnostics ship in every result     |

After P0, the full repeatable surface is:

```kotlin
// COLD — one-shot investigation of a Room database
val result = RoomDatabaseAnalyzer(SQLiteDatabaseSpec(dbPath)).use { it.analyzeOnce() }
result.entities.forEach { e -> println(e.entityTypeId + " -> " + e.tableBytes) }

// HOT — monitor that re-analyzes only when the database content changes
val monitor = RoomStorageMonitor(SQLiteDatabaseSpec(dbPath), scope = appScope)
monitor.results.watch { state -> ui.render(state) }   // emits on change only
// (Android) real-time: hook Room's InvalidationsTracker to monitor.refreshNow("room")

// GENERIC (any provider mix) hot monitoring
val reactive = ReactiveAnalyzer(analyzer, scope = appScope, policy = RefreshPolicy.Every(Duration.ofMinutes(5)))
```

## 2. Module layout and dependency policy

| Module                     | Depends on                          | Adds to consumers                 |
|----------------------------|-------------------------------------|-----------------------------------|
| `:reactive` → `storageanalyzer-reactive` | `:core`, `kotlinx-coroutines-core` | engine-agnostic rerun orchestration: `RerunController<T>`, `RerunState<T>`, `RefreshPolicy`, `ReactiveAnalyzer` |
| `:room` → `storageanalyzer-room` | `:core`, `:sqlite`, `:reactive` + `kotlinx-serialization-json` (runtime JSON DOM only, no compiler plugin) | Room schema-bundle parsing, descriptor auto-Generation, `RoomDatabaseAnalyzer` (cold), `RoomStorageMonitor` (hot) |

Rules kept from the original design:

- **No androidx dependency anywhere** (no `room-runtime` needed for P0 — see §5
  for how Room change notification still works without it).
- Coroutines enter only in `:reactive` (they become an `api` transitive for
  its consumers — a "boring" dependency every Kotlin project already has).
- `:room` packages: `com.mohamed.storageanalyzer.room`;
  `:reactive` packages: `com.mohamed.storageanalyzer.reactive`.
- Both modules ship with the same publishing pipeline (`storageanalyzer-`
  artifacts, sources + Dokka javadoc, MIT POM).

## 3. Hot vs cold semantics (the honest matrix)

| API                      | Semantics                                                                                                            |
|--------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `analyzeOnce()` (cold)     | Exactly one analysis, blocking/suspending only for the caller; no background state touched. Returns a complete result.   |
| `coldReportFlow()` (cold)  | Per collection = a fresh single-shot run (no shared state); dies after one emission unless re-collected.                 |
| `start()` on a monitor     | Starts the rerun loop: `Running → Completed/Failed → …` according to the `RefreshPolicy`. Cancellation- and failure-safe. |
| `state: StateFlow<RerunState<T>>` | Full lifecycle incl. `Running(previous)`, `Failed(error, previous)` — *stale-by-desig* last good result is visible, never silently dropped |
| `results: StateFlow<T?>`   | Completed values only, **suppressed when the digest is unchanged** (UI never re-renders on identical data)               |
| Failure                    | A failing turn is *state*, not a crash: `Failed(error, previous)`; the next turn retries. `CancellationException` propagates (cooperative stop). |
| Threads/parallelism        | All work runs on `Dispatchers.IO` (injectable); turns are serialized by a mutex; overlapping triggers wait, never race.    |
| Scope ownership            | The caller owns the `CoroutineScope`; `close()` cancels only this monitor's jobs, never the scope or the user's `StorageAnalyzer`. |

The change-detection mechanism is **digest-based**: every completed turn
computed a deterministic digest (` totals + resource count + per-resource
footprints + completeness`); a turn that equals the previous digest does not
publish. Data classes of `GlobalReport` are NOT compared directly — results
contain options with lambda fields and timestamps, making equality meaningless.

## 4. `:reactive` — generic rerun orchestration

```kotlin
public sealed interface RerunState<out T> {
    val at: Instant
    data class Idle(at)                                  // nothing computed yet
    data class Running<T>(at, previous: Completed<T>?)   // a turn is being computed
    data class Completed<T>(at, value: T, digest: String, durationMs: Long, previous: Completed<T>?)
    data class Failed<T>(at, error: Throwable, previous: Completed<T>?)
}

public sealed interface RefreshPolicy {
    data class Every(val interval: Duration)                                    // time-based
    data class OnChange(val probe: suspend () -> String?, val pollInterval: Duration) // cheap fingerprint, re-run only when it changes; probe failure = unknown → re-run
    object Manual : RefreshPolicy                                               // only explicit refreshNow/refresh calls
}

public class RerunController<T>(
    scope, policy, workDispatcher = Dispatchers.IO,
    produce: suspend () -> T,           // one analysis turn (may throw; may be blocking)
    digestOf: (T) -> String,
    emitOnlyOnDigestChange: Boolean = true,
) : AutoCloseable {
    val state: StateFlow<RerunState<T>>
    val results: StateFlow<T?>
    fun start()                     // idempotent; no-op for Manual
    suspend fun refreshNow(trigger: String? = null): RerunState<T>   // cold one-shot that also updates state
    fun refresh(trigger: String? = null)                             // fire-and-forget signal (for tracker hooks)
    override fun close()            // cancels own jobs; leaves user scope intact
}

public class ReactiveAnalyzer(          // convenience around StorageAnalyzer
    analyzer: StorageAnalyzer, scope: CoroutineScope,
    policy: RefreshPolicy = RefreshPolicy.Every(Duration.ofSeconds(30)),
    options: AnalysisOptions = AnalysisOptions.DEFAULT,
) : AutoCloseable {
    val state: StateFlow<RerunState<GlobalReport>>
    val results: StateFlow<GlobalReport?>
}
```

First-turn policy: the loop always computes one turn immediately at `start()`
— a monitor that starts empty and waits a full interval would be a
soundness trap. `Manual` never loops; callers drive it with
`refreshNow()`/`refresh(trigger)` — this is the Room *InvalidationTracker*
integration point (§6).

## 5. `:room` — what "Room integration" means at P0

Room's own storage analytics surface is: one SQLite file + sidecars
(default WAL), per-entity tables (rowid or WITHOUT ROWID tables), FTS
shadow tables, junction tables for relations, and its identity bookkeeping
(`room_master_table.identity_hash`, `PRAGMA user_version` = schema version).

`storageanalyzer-room` handles all of it **without a Room runtime
dependency**, in three layers:

### 5.1 Schema bundle = the descriptor source of truth

Room can export the canonical schema as JSON (`exportSchema = true`):
`<db-class>/<version>.json` with `{formatVersion, database:{version,
identityHash, entities:[{tableName, fields:[{columnName, affinity,
notNull, …}], primaryKey:[…], indices:[…], createSql, …}]}}`.

`RoomSchemaBundle.parse(path)` parses this **leniently** (unknown keys
ignored, required keys fail loudly) into:

```kotlin
data class RoomSchemaBundle(version, identityHash, entities: List<RoomEntitySpec>)
data class RoomEntitySpec(tableName, entityClassSimpleName, withoutRowid,
                          primaryKeyColumns, columnNames, indexNames, createSql,
                          val isFramework: Boolean /* room_master_table, android-metadata */)
```

Descriptor generation then reuses the **existing SQLite builder helpers verbatim**
(zero new identity logic — this is the "no repetitive code" pivot):

```kotlin
RoomDescriptors.entityDescriptors(bundle, sqliteProvider) : List<EntityDescriptor>
// per entity: provider.entityDescriptorForTable("room.<Class>", systemId, tableName)
//             — or entityDescriptorForPkTable(...) for WITHOUT ROWID tables
```

Without a bundle, descriptors degrade to live catalog introspection (labels
= table names, entityTypeId `room.<tableName>`) — analysis still works as a
graceful-tolerance fallback.

### 5.2 Cold single-shot: `RoomDatabaseAnalyzer.analyzeOnce()`

Wires the fs provider (parent of the db file; dedup makes the container file
counted once) **before** the sqlite provider, sets an *exclusion predicate*
so a crowded parent directory does not contribute unrelated files (only
`<db>`, `<db>-wal`, `<db>-shm`, `<db>-journal` pass), and produces:

```kotlin
data class RoomAnalysis(
    val dbFilePath: String,
    val liveSchemaVersion: Int?,       // PRAGMA user_version
    val identityHashLive: String?,     // SELECT identity_hash FROM room_master_table
    val bundleSchemaVersion: Int?, val identityHashBundle: String?,
    val drift: List<StorageDiagnostic>,     // identity mismatch → ERROR; table set drift → ERROR/WARN; version mismatch → WARN
    val entities: List<RoomEntityStorage>,  // descending by bytes
    val sidecars: List<SidecarStorage>,     // temp storage: wal/shm/journal roles
    val totals: RoomTotals(uniqueFootprint, dbFileBytes, pageCapacity, freelist, sidecarBytes),
    val global: GlobalReport,               // the full engine report — nothing truncated away
    val digest: String,                     // hot-loop change key
    val generatedAt: Instant, val durationMs: Long, val diagnostics: List<StorageDiagnostic>,
)
data class RoomEntityStorage(entityTypeId, tableName, displayName,
                             tableBytes: ByteCount?, indexesBytes: ByteCount?,
                             recordCount: Long?, rowsBytesSum: ByteCount?, maxRowBytes: ByteCount?)
```

- `tableBytes`/`indexesBytes` are derived from the already-measured
  inventory (`resourceSummaries`, `database_table_storage` /
  `database_index_storage`) — **no extra provider passes**.
- `recordCount` comes via `SELECT COUNT(*)` through the monitor's own
  read-only probe connection — a count of rows is *not a size* and is never
  mixed into byte metrics.
- `rowsBytesSum/maxRowBytes` are **opt-in** (`includeRowScan = false`
  default): they stream record encodings like §6.2, budget-capped — the
  payload-heavy path is never accidental.

### 5.3 Hot: `RoomStorageMonitor`

```kotlin
class RoomStorageMonitor(
    spec, schemaBundle = null,
    scope: CoroutineScope,
    pollInterval: Duration = 2.seconds,     // fingerprint probe cadence
    config: RoomAnalysisConfig = RoomAnalysisConfig(),
) : AutoCloseable {
    val state: StateFlow<RerunState<RoomAnalysis>>; val results: StateFlow<RoomAnalysis?>
    fun start(); refresh(trigger); suspend refreshNow; override close()
}
```

Change detection plumbing (the interesting Non-generic part):

- **Probe** (` RoomChangeProbe` in : as an interface): fingerprint against a
  single persistent read-only connection (`sqlite-jdbc` opened with
  `PRAGMA query_only=ON` + finite busy timeout; connection is recreated when
  the previous one fails), combining:
  1. `PRAGMA data_version` — increments when *another connection* commits;
     our own connection never even writes.
  2. `PRAGMA user_version` — catches schema migrations.
  3. the db file length — cheap implicit protection against replaced files
     (¬ normal reads might target the old inode after a replacement; a length
     jump forces Reconnect).
- **Granularity honesty**: SQLite reports change *per database*, not per
  table. A change in `posts` re-runs analysis of everything; per-table dirty
  detection would require triggers or Room runtime — both documented as P1
  enhancement, NOT faked.
- **Android real-time integration** (consumer-side, zero lib dep): Room's
  `InvalidationTracker` observer calls `monitor.refreshNow("room")`, bypassing
  polling latency entirely. Documented snippet in the USAGE guide.

## 6. Why no androidx dependency (and when it may come)

Room annotations are BINARY-retention → invisible to JVM reflection, so
runtime reflection-based descriptor generation is impossible; the KSP
processor (roadmap `processor`) would do it at compile time. The schema
bundle is deterministic, versioned, and already produced by every Room
project — no new build-time step. If `room-runtime` bridge modules users
strap on later (Android), P1 can offer `RoomInvalidationObserver` as a
separate artifact that depends on androidx; the P0 surface changes not.

## 7. Threading, budgets, and honesty while hot

- Per-turn `AnalysisContext.create(options.budget)` — a monitor inherits the
  budget honesty of the cold path: a slow turn ends `PARTIAL` and is still
  published (with completeness visible). Never guess.
- The probe connection is single-flight cheap (3 pragmas + file length);
  polling is safe to leave running all day; `data_version` does not
  bump from dead reader attachments.
- Emission interruption: consumers read `results.target` under normal
  suspension; backpressure is unnecessary (conflated at StateFlow level).
- `Failed` runs do NOT fake; the failed turn leaves last good result intact
  and age-visible through `RerunState.Failed.error`/`previous.at`.

## 8. Test matrix (shipped with P0)

| Area | Assertions |
|------|------------|
| Bundle parse | version/identityHash/entities/indices/pk columns; filename fallback; lenient unknown-key tolerance |
| Descriptor generation | rowid + WITHOUT ROWID tables; framework tables excluded; label mapping (class short name) |
| Cold analysis | per-entity table/index bytes > 0 and correct ordering; sidecars (WAL) measured with a holder connection; recordCount matches inserts; unrelated dir files excluded from uniqueFootprint |
| Drift | extra/missing live table → diagnostic; identityHash mismatch → ERROR; user_version → WARN; clean schema → empty drift |
| Reactive generic | `Every` loop publishes repeatedly; digest suppression works; `Failed` recovers; Manual policy only runs on request; cold `refreshNow` w/o start; close() stops the loop |
| Room monitor | baseline emit at start; insert via second connection → within poll budget a new emission with larger row count; fingerprint failure re-analysis; manual `refresh(trigger)` bypasses probe |
| Integrity invariant | No test relies on wall-clock delays longer than ~5 s |

## 9. Phasing shipped in this change

- **P0 (implemented)**: everything in §4/§5 with the test matrix of §8;
  publishing extends automatically to `storageanalyzer-reactive` /
  `storageanalyzer-room`.
- **P1 (next)**: androidx `room-runtime` observer bridge artifact,
  per-table trigger-based dirty flags, fs `WatchService` `RefreshPolicy`
  variant for `:filesystem` hot monitoring.
- **P2**: KSP processor generating bundle-free descriptors at compile time
  (respects §17 roadmap ordering); Room-specific `TEMPORARY_STORAGE`
  checkpoint accounting refinements.

## 10. Risks and honest limitations

| Risk | Mitigation / rejection note |
|------|------------------------------|
| `data_version` can lag on some platform quirks (Windows + delete + recreate file) | file-length probe + single-reconnect; docs describe both traces |
| One change → full re-analysis (per-table granularity deferred) | capped by budgeted, cheap default metrics; P1 triggers optional |
| Crowded db parent dir inflates discovery cost | exclusion predicate drops non-db files from measurement; narrowing discovery itself is P1 |
| kotlinx-serialization-json added to `:room` consumers | used purely as DOM parser, no compiler plugin, no Kotlin stdlib semantic; cost documented (few MB) |
| Room legacy module versions without `formatVersion` shape | lenient parser accepts root- or database-wrapped variants |
| Consumers forgetting `close()` (JDBC probe handle) | `AutoCloseable` everywhere; docs + Android usage lifecycle examples |
