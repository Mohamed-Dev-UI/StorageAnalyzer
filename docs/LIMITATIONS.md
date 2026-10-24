# Limitations

Honest limitations of the shipped modules (`:core`, `:database-api`,
`:sqlite`, `:filesystem`, `:history`, `:reactive`, `:room`, `:testing`,
`:example` and the Android `:app`). Anything not listed as supported should be
assumed unsupported until documented here.

## Filesystem provider (`:filesystem`)

- **Allocation size**: the JVM exposes no portable cluster size. Unless an
  `allocationUnitResolver` is supplied by the host, `FILE_ALLOCATED_SIZE` is
  `UNAVAILABLE` (with a reason) — never approximated.
- **Sparse files**: native `st_blocks` access is unavailable in a portable
  API; only heuristic suspicion (`rounded < logical`) is reported.
- **Extended attributes**: xattr bytes exist on most systems but are not
  measurable portably → `METADATA_STORAGE` is `UNAVAILABLE` per file, honest
  reason attached.
- **Symlinks**: never followed (cycles, and following could lurch across
  permission/volume boundaries). Each skip produces a diagnostic (throttled
  at 20 per run).
- **Hard links**: deduplication requires `attr.fileKey()`; on platforms/JDKs
  without inode identity, hard-linked paths are counted per path (with a
  capability note).
- **Volume capacity**: requires a resolvable `FileStore` or a host-supplied
  `volumeStatsResolver` (e.g. Android `StatFs`, where scoped-storage NIO has
  no store). Host numbers are marked `fs.host_volume_stats` with the
  `fs.volume.host-supplied` limitation.
- **Reserved capacity**: no portable API → unavailable unless the host resolver
  reports it.
- **Race-prone trees**: files/directories vanishing mid-scan produce honest
  UNAVAILABLE measurements and `fs.dir_unreadable` warnings; they do not
  produce zero-footprint entries.
- Windows-specific: identities use `File.canonicalFile`; NTFS nuance
  (8.3 names, cascading junctions treated as directories) is *not*
  specially handled — junction points are traversed as directories.

## SQLite provider (`:sqlite`)

- Uses the **bundled sqlite-jdbc driver** (3.41.2.2 / SQLite 3.41.2 known
  release-tested). Builds differ: without `dbstat`, table/index storage is
  `UNAVAILABLE` with a documented reason instead of an estimate.
- **Per-record sizes are CONVENTION** — exact row payloads under the
  documented record format, but *not* including cell/page/wall overhead.
- **Freelist semantics**: freelist *space* is exact; its *breakdown inside
  the freelist* is not attempted.
- **Locked databases**: busy timeout is configurable; genuinely locked DBs
  surface as diagnostics with open failure reasons (capability probe marks
  the system unhealthy).
- **WAL sidecars**: WAL files are honestly sized *only* while a connection
  is alive; the analyzer never mutates the database to induce a checkpoint.
- **WITHOUT ROWID + multi-column keys**: supported via pk-addressed record
  identities; unusual schemas with `:` in identifiers rely on the
  percent-encoding scheme (tested).
- **Views**: analyzed only as schema objects, not as data containers.
- **JVM only**: `:sqlite` needs `org.xerial:sqlite-jdbc`, including its native
  binaries. The Android showcase deliberately does not embed it.

## History (`:history`)

- The snapshot format is **v1 text** — no binary codec, no async compaction;
  one file per snapshot. Corrupt files abort loudly (never skipped
  silently).
- Comparisons only compare a metric when it is present in **both**
  snapshots; one-sided metrics are listed as not comparable (`no fake zeros`).
- Growth model = ordinary least squares over the footprint; it does **not**
  model seasonality, compaction events or quota caps; accuracy class is
  ESTIMATED.
- `InMemorySnapshotStore` keeps everything in memory (test/demo scale).

## Reactive (`:reactive`)

- **Turn granularity**: change detection via `RefreshPolicy.OnChange` polls a
  fingerprint on a timer; sub-interval bursts collapse into one turn
  (mutex-serialized) — this is deliberate, not a race.
- **Digest cost**: `AnalysisDigest` builds an O(report) string per turn;
  constant factors are small (metrics + stable keys) but it is never free.
- **Hot flow delivery** is at-most-one-lagging `StateFlow` (collector speed
  never back-pressures analysis); slow collectors keep only the latest value.
- `ReactiveAnalyzer` reuses the caller's `StorageAnalyzer`; it does not
  snapshot-compare between turns and does not watch the filesystem itself —
  change detection is via the supplied `RefreshPolicy` only.

## Room integration (`:room`)

- **Zero androidx dependencies by design**: entity knowledge comes from Room's
  exported schema bundle JSON (`exportSchema = true`). No runtime reflection
  over `@Entity` annotations (they are binary-retention, invisible to JVM
  reflection anyway).
- **Bundle may lag the live schema**: drift is *diagnosed* (identity-hash /
  version / missing & extra tables — see the contract table in
  `docs/USAGE.md`, Scenario K), never silently masked. Live-only extra tables
  are still analyzed.
- **Change detection is per-database, not per-table**: `PRAGMA data_version`
  (plus `user_version`, page count, file length) changes when *any* connection
  commits, so an unrelated table's write also schedules a turn; identical
  content is then suppressed by digest. Per-table dirty tracking is
  impossible portably at the SQLite level; for instant semantics hook Room's
  `InvalidationTracker` into `monitor.refresh()` (an androidx `room-runtime`
  bridge artifact is planned, not shipped).
- **Fingerprinting needs a file-backed database** (`:memory:`,
  `jdbc:`/`file:` URIs degrade to `RefreshPolicy.Manual` — drive with
  `refreshNow()`/`refresh()`).
- **Needs a SQLite JDBC driver at runtime** (not re-bundled; consumers of
  `:sqlite` get xerial transitively at runtime scope). On Android this means
  the native sqlite footprint of `sqlite-jdbc` — do not adopt `:room` in an
  app module unless that is acceptable.
- **Row counts** are `SELECT COUNT(*)` reads through a separate read-only
  probe connection (a count is a count — never attributed into byte totals).
- **`includeRowScan` records/streaming** is opt-in and budget-capped, but it
  is one extra pass over every row per entity; off by default.
- Multi-process writers are detected through `data_version`; a checkpoint
  that only rewrites the WAL file may also schedule a turn — digest
  suppression keeps identical content from re-emitting.
- **JVM only**: like `:sqlite`, `:room` requires the JDBC driver at runtime
  and is not part of the Android app.

## Android showcase (`:app`)

- Demonstrates the JVM-safe modules (`:core`, `:filesystem`, `:history`,
  `:reactive`) and custom providers; `:sqlite`/`:room` analytics is covered by
  JVM tests in the same module and by `:example`, not by the APK.
- Uses an app-private demo inventory. It does not request broad-storage or
  `MANAGE_EXTERNAL_STORAGE` permissions.
- The allocation unit is a fixed demo value (4096 bytes), so allocated sizes
  are CONVENTION-grade estimates; volume numbers come from `StatFs` through
  the host resolver and keep their own provenance.
- Snapshots are demo data in an androidx Room database; the app is a reference
  consumer, not a persistence product.

## Core

- Deterministic, single-threaded orchestration as shipped. Providers must
  document their own thread-safety if they differ.
- `MeasurementCache` exists as an SPI; only `NullMeasurementCache` ships —
  caching decisions (including invalidation) remain with consumers.
- Custom `MetricType`/`MeasurementMethod` ids are validated to prevent
  collisions with frozen built-ins.
- `AnalysisOptions.redactLabels` is currently applied to human labels
  where they appear (e.g. overlap measurement labels) — `stableKey`s of
  resources are intentionally NOT redacted, because dedup and growth
  attribution depend on them; hash those yourself if full anonymization is
  required for publication.

## Deferred modules

The following modules are documented but **not implemented in this release**:

| Module           | Intended content                                                  | Why deferred |
|------------------|--------------------------------------------------------------------|--------------|
| `processor`      | KSP annotation processor generating `EntityDescriptor`s (and bundle-free Room descriptors) from Room/SQL DAOs | build-infra heavy; no impact on runtime analytics |
| `object-storage` | S3/OSS/Azure-style object store provider (list + HEAD based)       | requires cloud credentials/FIXTURES in CI |
| `serialization`  | official wire formats for reports/snapshots (beyond `SnapshotFormat v1`) + schema declarations | format stability review before freezing |

Until these land, consumers may implement `StorageProvider` themselves — the
SPI covers the same features (see [ARCHITECTURE.md](ARCHITECTURE.md) extension
points). Room database analytics is shipped through `:room`; the planned
androidx `room-runtime` bridge would only add automatic invalidation.
