# Final Report — Storage Analyzer library

> **Internal record.** This is a historical implementation report, not part of
> the public documentation. Test counts and status reflect the date below, not
> the current tree; see the README for current numbers.

Build-out report for the universal storage analytics library, generated at
the end of the implementation pass (2026-10-24). All facts below are from
actual runs of the repository as committed at this point.

## Scope delivered

A production-quality, **technology-independent** storage analytics library:
the core model is free of database-engine / ORM / DI dependencies, storage
technologies plug in through public SPIs (`StorageProvider`,
`RecordAnalyticsEngine`, `EntityResourceResolver`). Anything unmeasurable is
reported `UNAVAILABLE` with an explicit reason — there are no silent zeros
anywhere in the shipped code paths, and every measurement carries accuracy
class + provenance.

### Modules

| Module          | State     | Contents (essentials) |
|-----------------|-----------|------------------------|
| `:core`         | complete  | measurement model (`ByteCount` checked arithmetic, `MetricType`+dimension, `SingleMeasurement`, `MeasurementProvenance`), identity (`ResourceIdentity`, `PathIdentity` canonical FS scheme), resource model (`StorageResource`, categories, owners, reference links), config/options/budgets/contexts, `StorageProvider` SPI + registry + capabilities, accounting policies, `InventoryComposer` (dedup) + `GlobalReportBuilder` (reconciliation), entity engine (descriptors, `EntityAnalyzer`, relation walker with termination + budgets), reports (`GlobalReport`, `EntityReport`, `RecordBreakdown`), snapshot summary, cache SPI, facade (§16 `StorageAnalyzer`) |
| `:database-api` | complete  | engine-agnostic relational contracts (`DatabaseIntrospector`, column/pk metadata incl. `pkOrdinalInt`) |
| `:sqlite`       | complete  | read-only SQLite provider: identity codecs (`SQLiteResourceIds` incl. pk `\u001F` scheme), introspection (tables/indexes/pages/dbstat/rowid-alias detection), connection lifecycle (readonly retry for WAL-mode opens + `query_only`, session UDFs `varint_len_tf`/`int_serial_payload`), record-format math (`SQLiteRecordFormat`), provider (db/table/index/record/sidecar/measure dispatch, page reconciliation diagnostics, health incl. read-only `integrity_check`), entity analytics (resolver, reverse-FK + junction relations, descriptor helpers) |
| `:filesystem`   | complete  | read-only `java.nio` provider: DFS streaming discovery incl. the scan root as volume facade, exact logical sizes, resolver-based CONVENTION allocated size and host-supplied volume stats (Android `StatFs`; honest UNAVAILABLE otherwise), hard-link dedup via `fileKey`+`decomposes`, symlink never-follow with throttled diagnostics, category map, `related()` CONTAINS edges, health checks, cancellation |
| `:history`      | complete  | `SnapshotStore` SPI + `InMemorySnapshotStore` + atomic `FileSnapshotStore` (versioned `SnapshotFormat v1`, strict decode, percent-escaped keys, custom-metric round-trip), `SnapshotComparison` (metric/category deltas, forward-only guard, honest non-comparable annotations, resource attribution created/removed/changed), `GrowthModel` (OLS fit, r², negative predictions unclamped, honest UNAVAILABLE) |
| `:reactive`     | complete (P0) | `RerunController<T>` (mutex-serialized turns, `RerunState` Idle/Running/Completed/Failed with `previous` chaining, digest-based result suppression, injectable dispatcher), `RefreshPolicy` (`Every`/`OnChange` probe/`Manual`), `ReactiveAnalyzer` (`StorageAnalyzer` wrapper), `AnalysisDigest` (deterministic content key of a `GlobalReport`), `coldReportFlow` |
| `:room`         | complete (P0) | `RoomSchemaBundle` (lenient parser incl. `<n>.json` version fallback), `RoomDescriptors` (bundle/live-catalog → descriptors via the sqlite helper functions, rowid + WITHOUT ROWID), `RoomDbProbe` (persistent read-only fingerprint connection: `data_version`/`user_version`/pages/length), `RoomDatabaseAnalyzer.analyzeOnce()` (fs-first dedup + sidecar-only exclusion, per-entity table/index bytes + row counts, opt-in row scan, drift diagnostics), `RoomStorageMonitor` (hot StateFlow, cold `refreshNow()`, pluggable `RoomChangeProbe`) |
| `:testing`      | complete  | `RecordingContext` (scriptable budgets/cancellation/diagnostics), `TempDir`, identity builders |
| `:example`      | complete  | runnable `StorageAnalyzerDemo` (`gradlew :example:run`) wiring fs+sqlite with cross-provider dedup, snapshot history with growth event, comparison + forecast printing; manual benchmark probe (property-gated) |
| `:app`          | complete  | Android showcase app: Hilt DI, Room + KSP (schema exported, app-owned snapshot codec/store), Compose/Material 3, repositories + ViewModels, custom SPI providers, live monitoring, snapshots/forecast, budgets/cancellation, privacy knobs, diagnostics and an in-app implementation guide; JDBC-only features are covered by JVM tests |

Deferred (documented roadmap only, no stub code): KSP `processor`,
`object-storage`, `serialization` — see docs/LIMITATIONS.md. The Room
integration P0 scope **is** shipped (`reactive` + `room` modules).

## Test results (actual last runs)

Full `./gradlew build` (including `:app`): **BUILD SUCCESSFUL**.

| Suite                                  | Tests | Failures | Errors |
|----------------------------------------|-------|----------|--------|
| `:core` (7 test classes)               | 39    | 0        | 0      |
| `:sqlite` (`SQLiteProviderTest`)       | 15    | 0        | 0      |
| `:filesystem` (`FilesystemProviderTest`)| 15   | 0        | 0      |
| `:history` (`HistoryModuleTest`)       | 14    | 0        | 0      |
| `:reactive` (`ReactivePipelinesTest`)  | 8     | 0        | 0      |
| `:room` (`RoomIntegrationTest`)        | 9     | 0        | 0      |
| `:example` (`ExampleSmokeTest`)        | 2     | 0        | 0      |
| `:example` (`UsageExamplesTest`)       | 13    | 0        | 0      |
| `:app` (4 test classes)                | 19    | 0        | 0      |
| **Total**                              | **134**| **0**   | **0**  |

The `:app` module additionally has an instrumented smoke test
(`ShowcaseSmokeTest`, run via `./gradlew :app:connectedDebugAndroidTest`)
that boots the showcase on a device and checks every main surface.

Additionally, 3 benchmark probes (`BenchmarkProbeTest`) are property-gated
(`-PwithBenchmarks=true`) and skipped in regular runs.

**Docs validation harness:** every scenario in docs/USAGE.md has a
corresponding executable function in
`example/src/main/kotlin/com/mohamed/storageanalyzer/example/UsageExamples.kt`
and is asserted by `UsageExamplesTest` on every build — top-N reporting,
fast metric paths, SQLite schema/record analytics, cross-provider dedup,
snapshot comparison + growth fit, entity accounting via the facade, budgeted
partial runs, cooperative cancellation, and a full custom-provider SPI
round-trip. Documentation rot is a build failure now.

Coverage notes (from the suites themselves):
- core: dedup/inventory semantics (§11), identity stability, checked
  arithmetic, policy conservation, relation walker termination/budgets.
- sqlite: exact dbstat sums, record-format sizes (rowid + pk tables,
  hostile identifiers), freelist reconciliation diagnostics, WAL sidecar
  liveness, corrupt-file health, cancellation, FK/junction relations,
  streaming record resolve.
- filesystem: identity equality with `PathIdentity`, exact sizes, volume
  facade (FileStore + host resolver precedence and honesty), CONVENTION
  rounding, honest unavailability, hard-link dedup
  (fileKey-conditional), overlap dedup keys, symlink skip, missing root,
  vanished file, cancellation, category map.
- history: codec round-trips (hostile keys, custom metrics), strict
  corruption failure, store replay order, comparison honesty annotations,
  hand-computed OLS numbers, unclamped negative trends.

## Benchmark results (probe run on this machine, 2026-10-24)

`./gradlew :example:test -PwithBenchmarks=true --rerun-tasks` (median of 5
measured passes, 1 warm-up excluded):

| Scenario                              | median |
|---------------------------------------|--------|
| filesystem.discover_800               | 732 ms |
| filesystem.full_scan_800              | 2,656 ms |
| sqlite.schema_discover_and_measure    | 286 ms |
| sqlite.record_level_1000_rows         | 133 ms |
| history.compare_5000_details          | 3.9 ms |
| history.forecast_60_points            | 0.6 ms |

Methodology + caveats in docs/PERFORMANCE.md.

## Documentation set delivered

- `README.md` (what it does, module map incl. §16 name mapping, quickstart, scenario index, test status)
- `docs/USAGE.md` (**developer cookbook** — consumption options, ten verified
  scenarios, performance playbook, error model, troubleshooting, FAQ; every
  scenario executed by `UsageExamplesTest`)
- `docs/ARCHITECTURE.md` (module graph, core package map, SPI contract,
  identity schemes, threading, extension recipe)
- `docs/MEASUREMENT_ACCURACY.md` (accuracy ladder + per-provider matrices)
- `docs/ACCOUNTING.md` (views, dedup/aliases, policies, reconciliation
  `attribution_conservation`, overflow integrity)
- `docs/LIMITATIONS.md` (honest gaps + deferred modules table)
- `docs/COMPATIBILITY.md` (JVM/Kotlin/engine matrices)
- `docs/RELEASE.md` (checklist incl. `:app`-untouched rule, benchmark re-run,
  changelog/publish/rollback policy)
- `docs/PERFORMANCE.md` (this-repo measured numbers, reproduction command)
- `docs/internal/FINAL_REPORT.md` (this document)

Consumability additions coordinated with the docs: `group`/`version`
(`io.github.mohamed-dev-ui` / tag-derived with `0.1.0` fallback) on all
library modules, full **Maven Central publishing pipeline** (vanniktech
maven-publish plugin 0.37.0 + Dokka 2.2.0 javadoc jars): signed, sources and
javadoc-bearing `io.github.mohamed-dev-ui:storageanalyzer-*` artifacts,
configurable from the root build; GitHub Actions workflow
(`.github/workflows/publish.yml`) publishes and releases on `v*` tag push;
`AnalysisOptions.redactLabels` is now honored where labels surface
(overlap measurement labels) — stable keys intentionally stay unredacted
(see docs/LIMITATIONS.md).

## Notable engineering decisions worth highlighting

1. **No silent zeros as an invariant, not a convention** — enforced by
   MeasurementState + map of unmeasured reasons + completeness labels;
   comparisons treat one-sided metrics as *not comparable* instead of 0.
2. **Cross-provider dedup via identity aliases** — SQLite db/sidecar
   resources declare `alsoDiscoverableBy` entries in the canonical
   PathIdentity scheme so a `.sqlite` container is counted once globally,
   with both provider views still fully visible.
3. **Honest UNAVAILABLE everywhere** — e.g. allocated size with no
   resolver, dbstat-less builds, locked roots, vanished files, xattr space.
4. **Budgeted honesty** — `BudgetedAnalysisContext` gates every traversal
   step; partial runs are labeled PARTIAL with diagnostics instead of
   silently truncated.
5. **Versioned text snapshot format with strict decode** — corrupted
   storage aborts with the offending file instead of returning less data.
6. **sqlite-jdbc 3.41.2.2 quirks are codified** (fresh `Statement` per
   execute; comment-only statements filtered; `page_count==0` on empty
   `:memory:`; WAL readonly-open fallback) — captured in provider code +
   test suite comments.

## Known non-goals / follow-ups

- License file + publishing config (docs/RELEASE.md checklist).
- P1: androidx `room-runtime` bridge (automatic `InvalidationTracker` →
  monitor refresh) and P2: KSP descriptor generator; object-store provider
  and formal serialization module (roadmap documented).
- Performance: per-resource `FileStore` probing dominates full-scan cost;
  a narrowed metric query avoids it (documented), a per-system `FileStore`
  cache is a candidate future optimization.
