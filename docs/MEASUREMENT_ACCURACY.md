# Measurement Accuracy

Every value in this library is annotated with an accuracy class, a method tag
and a timestamp, in `MeasurementProvenance`. Users can always ask "where does
this number come from?" — and the library never downgrades accuracy silently.

## The accuracy ladder

| Class        | Meaning                                                          | Examples |
|--------------|------------------------------------------------------------------|----------|
| `EXACT`      | Directly measured by the storage technology, no interpretation    | file length (`FILE_LENGTH`), `PRAGMA page_count`, dbstat B-tree page sums, record bytes serialized into the store format when the engine hands them out |
| `CONVENTION` | Exact under a documented accounting convention                    | SQLite record sizes computed with the documented record format — exact encoding size, excludes per-cell/page overhead; filesystem allocation = logical length rounded to the host-supplied cluster size (`ROUND_TO_CLUSTERS`) |
| `ESTIMATED`  | Point estimate of a documented model                              | proportional allocation of table pages to rows; growth-model projections |
| `PARTIAL`    | Correct for the measured subset only                              | budget/depth-limited traversals |
| `UNAVAILABLE`| Not measurable through supported APIs (value is absent + reason)  | allocated size when the platform exposes no cluster size and no resolver was supplied; free space under a locked volume; `integrity_check` after capability probe |
| `UNKNOWN`    | The metric is reported but this run does not cover it             | runs whose providers list a metric but nothing answered (never produced silently) |

## Never-zero guarantee (no silent zeros)

Measurement states are distinct from values:

- `MEASURED` — value + provenance present.
- `UNAVAILABLE` — reason present, value absent; unchanged totals, no zero.
- `NOT_APPLICABLE` — the metric structurally does not apply (e.g. volume
  capacity on a nested directory; xattr-space in the SQLite scheme).

Aggregations only ever add `MEASURED` values. Unavailability is *carried
into summaries* (completeness labels, diagnostics, `StorageMeasurement.missing()`)
instead of being zero-padded.

## Per-provider accuracy matrix

### `:filesystem` (`FilesystemProvider`)

| Metric                          | State when measured | Accuracy   | Method              |
|---------------------------------|---------------------|------------|---------------------|
| `STORED_CONTENT_SIZE` / `FILE_LOGICAL_SIZE` | MEASURED (regular file) | EXACT  | `fs.file_length` |
| `FILE_ALLOCATED_SIZE` (resolver supplied)   | MEASURED             | CONVENTION | `fs.round_to_clusters` (limitation `fs.allocated.rounds-up`) |
| `FILE_ALLOCATED_SIZE` (no resolver)         | UNAVAILABLE          | UNAVAILABLE| reason documented on the measurement |
| `TOTAL_CAPACITY` / `FREE_CAPACITY` (scan root only) | MEASURED      | EXACT      | `fs.volume_space_stats` |
| `TOTAL_CAPACITY` / `FREE_CAPACITY` (host resolver)  | MEASURED      | EXACT      | `fs.host_volume_stats` (limitation `fs.volume.host-supplied`) |
| `RESERVED_CAPACITY`             | UNAVAILABLE (portable JVM API exposes none; measured when the host resolver reports it) | — | — |
| directories (except root volume facade) | NOT_APPLICABLE for capacity/volume metrics | — | no double counting of bytes |
| special entries (devices, pipes) | NOT_APPLICABLE + INFO diagnostic | — | — |
| symlinks                        | skipped (never followed) + `fs.symlink.skipped` diagnostics | — | — |
| hard-link duplicates            | measured normally; `decomposes` marks the second path so unique sums count shared bytes once | — | warning `fs.hardlink.shared-inode` |
| sparse-suspicion                | warning `fs.sparse-suspected` when rounded < logical (heuristic only) | — | — |
| xattr / `METADATA_STORAGE`      | UNAVAILABLE — "exists but not measurable via the portable java.nio provider" | — | — |

### `:sqlite` (`SQLiteStorageProvider`)

| Metric                                    | Accuracy      | Method                       |
|-------------------------------------------|---------------|------------------------------|
| `STORED_CONTENT_SIZE` (db-level)          | EXACT         | file size / `PRAGMA page_count × page_size` |
| `DATABASE_PAGE_CAPACITY`                  | EXACT         | `sqlite.pragma_page_size` + `pragma_page_count` |
| `DATABASE_TABLE_STORAGE` / `DATABASE_INDEX_STORAGE` | EXACT (dbstat builds) | `sqlite.dbstat_btree_sum` — exact for the B-tree as a whole; UNAVAILABLE with reason on builds without dbstat |
| `DATABASE_FREELIST_SIZE`                  | EXACT         | `PRAGMA freelist_count` × page size |
| `DATABASE_RECORD_SIZE`                    | CONVENTION    | `sqlite.record_format` — per-row serialized encoding incl. header/varints; excludes cell/page overhead |
| WAL/journal sidecars                       | EXACT        | file length + liveness scan (alive only while a holder is open) |
| consistency (`integrity_check`)           | diagnostic    | `sqlite.pragma_integrity_check`; ERROR diagnostic when failing |

Reconciliation: provider-level page totals vs. per-table sums plus freelist
produce explicit `ReconciliationDifference` entries with verdicts
(`RECONCILED` / `EXPECTED_DIFFERENCE` with an explanation / `UNEXPECTED_DIFFERENCE`).
Differences are reported, never absorbed.

### `:room` (`RoomDatabaseAnalyzer` / `RoomStorageMonitor`)

The Room integration introduces **no new measurement methods** — every byte
value it reports is a value already measured by `:filesystem` or `:sqlite`
and therefore inherits the accuracy class above:

| Reported value                                   | Source & accuracy                                              |
|--------------------------------------------------|----------------------------------------------------------------|
| `RoomTotals.uniqueFootprint`                     | engine totals — EXACT per the underlying measurements, dedup once |
| `RoomTotals.dbFileBytes` / `sidecars[].bytes`    | file length — EXACT (`fs.file_length`); sidecars only while a connection is open |
| `RoomTotals.pageCapacity` / `freelist`           | `PRAGMA`-based — EXACT (`sqlite.*`); recovered from the dedup overlap view so bytes are never double counted |
| `RoomEntityStorage.tableBytes` / `indexesBytes` | dbstat B-tree sums — EXACT on dbstat builds, UNAVAILABLE otherwise (never estimated) |
| `RoomEntityStorage.recordCount`                  | `SELECT COUNT(*)` — EXACT count, deliberately a *count*, never attributed into byte totals |
| `RoomEntityStorage.rowsBytesSum` / `maxRowBytes` | sum/max of record-format sizes — CONVENTION (opt-in `includeRowScan`) |
| `drift` list                                     | diagnostics, not measurements; severities are part of the contract (see Scenario K in `docs/USAGE.md`) |

`null` values in `Room*Storage`/`RoomTotals` mean *not measured this run*
( unavailable metric, probe unreachable) — never a silent zero. The
`digest` is a *change key*, not a measurement.

## Snapshot & history accuracy

- `StorageSnapshot` values inherit the accuracy of the run that produced
  them: `completenessLabel` (`COMPLETE` / `PARTIAL` / `UNKNOWN`) travels with
  every snapshot and every comparison.
- `SnapshotComparison` metric deltas are exact *over stored snapshot numbers*;
  accuracy is `EXACT` only when **both** runs were `COMPLETE`, otherwise
  `PARTIAL`/`UNKNOWN` — comparisons never manufacture a precise delta from
  partially covered runs.
- Growth modelling (`GrowthModelFactory.fit`) is `ESTIMATED` by definition;
  linear extrapolation, `r²` reported, negative predictions are *not* clamped
  (a clamped "positive memory" would be fabrication).

## Link between accuracy and provenance bookkeeping

`MeasurementProvenance` carries: `method` (`MeasurementMethod`, frozen id or
`x_<provider>.<custom>`), `accuracy`, `measuredAt`, `providerId`, `backend`
(e.g. `java.nio (JDK 17.0.9)`, `SQLite 3.41.2`), plus `limitations`,
`warnings`, and a short `note`. Custom providers must declare a method id
with the `x_<name>.` prefix; reuse of a stable built-in id requires the exact
same semantics.
