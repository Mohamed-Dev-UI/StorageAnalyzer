# Performance

Real, measured numbers from this repository's benchmark probe
(`BenchmarkProbeTest` in the `:example` module). Every release's performance
claims must be backed by a fresh run of that probe on documented hardware —
no theoretical numbers, no "should be fast"-claims.

## How to reproduce

```bash
./gradlew :example:test -PwithBenchmarks=true --rerun-tasks
```

The probe executes one warm-up pass per scenario (excluded) and then 5
measured passes; `medianMs` is the headline number, `minMs`/`maxMs` bound the
variance of the very same values printed inside the test XML. The default
test suite always skips the probe (JUnit assumption) so CI stays bounded.

## Environment of the numbers below

| Aspect      | Value                                                             |
|-------------|-------------------------------------------------------------------|
| OS          | Windows 11 (NTFS system drive, `%TEMP%` on the same volume)       |
| JVM         | Gradle test worker, JDK 17 toolchain (`jvmToolchain(17)`)         |
| Date        | 2026-10-24                                                        |
| Interpreter | Not applicable (pure JVM workload; no JIT tiering manipulations)  |

PCIe SSD system drive, no parallelism inside the analyzer (single-threaded
design as shipped), OS file caching active — discovery therefore measures
warm-cache behavior, which is the realistic case for repeated analytics runs.

## Measured scenarios

### Filesystem provider (800 small files in 3 directories)

| Scenario                                        | median | notes                                        |
|-------------------------------------------------|--------|----------------------------------------------|
| `filesystem.discover_800` (inventory only)      | 732 ms | 803 resources (800 files + 3 directories incl. root) |
| `filesystem.full_scan_800` (inventory + measure every resource with the default metric set) | 2,656 ms | ≈3.3 ms per resource incl. `FileStore` lookups for volume metrics |

Notes:
- Discovery streams DFS entries via `java.nio`; attribute probing uses
  `BasicFileAttributes` reads and (on NTFS) `fileKey` for hard-link detection.
- The full-scan cost is dominated by per-resource `FileStore` probes for
  capacity metrics and allocation-unit resolution. Scans that do not ask for
  volume metrics (via the metric query set) avoid those probes.

### SQLite provider (bench.sqlite: `users` 1,000 rows + `events` 4,000 rows, 2 indexes)

| Scenario                                          | median   | notes                                                    |
|---------------------------------------------------|----------|----------------------------------------------------------|
| `sqlite.schema_discover_and_measure`              | 286 ms   | database + tables + index + sidecar discovery and default-set measurement |
| `sqlite.record_level_1000_rows`                   | 133 ms   | per-record validation + `DATABASE_RECORD_SIZE` measurement of every row of `users`; ≈134 µs per row |

Notes:
- Schema-level totals come from PRAGMA page accounting plus dbstat aggregates
  where the build provides them (EXACT); per-record sizes are computed with
  the SQLite record-format math (CONVENTION).
- Record-level streaming is the cheapest analytical operation per row because
  each row is addressed by rowid and measured without page-level queries.

### History (snapshots, comparison, forecast)

| Scenario                              | median | notes                                        |
|---------------------------------------|--------|----------------------------------------------|
| `history.compare_5000_details`        | 3.9 ms | full comparison incl. per-resource attribution over 5,000 detailed resources |
| `history.forecast_60_points`          | 0.6 ms | least-squares fit over a 60-point series     |

## Honest caveats

- Absolute numbers are indicative of *this machine only*. Reproduce on target
  hardware before drawing conclusions for sizing decisions.
- `filesystem.full_scan_*` includes per-file volume-probe overhead that a
  narrowed metric query set avoids; budget-limited runs (bounded context)
  reduce both time and coverage — completeness is reported, never silently
  truncated.
- SQLite numbers use the bundled sqlite-jdbc 3.41.2.2 driver with one open
  read-only connection per run; results vary with WAL mode, index count and
  database size.
- The probe prints min/median/max so failed-hardware runs (thermal throttling,
  other I/O) are visible instead of hidden behind an average.
