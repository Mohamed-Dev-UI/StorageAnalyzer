# Architecture

Layering, module graph and extension points of the Storage Analyzer library.
See [README.md](README.md) for the documentation index,
[API.md](API.md) for the public surface and [ACCOUNTING.md](ACCOUNTING.md) for
the accounting guarantees.

## Module graph

```
        ┌─────────────────────────────────────────┐
        │  :app (Android showcase consumer)       │
        └───────────────┬─────────────────────────┘
                        │ Hilt, Room/KSP, Compose
        ┌───────────────▼───────────────────────────────┐
        │  :example (demo + validated doc scenarios)    │
        │  ┌────────────────────────────────────────────┤
        │  │ :filesystem :sqlite :history :reactive :room
        └──┴────────────────┬──────────────────────────┘
                            │
        ┌───────────────────▼───────────────────────────┐
        │  :core  (+ :database-api relational contracts)│   ← the only real boundary
        │  (no engine / ORM / DI / platform deps)       │
        └───────────────────────────────────────────────┘

        :reactive (orchestration layer:  :core + kotlinx-coroutines)
        :room     (composition layer:    :core + :sqlite + :reactive + JSON-DOM parser)
        :testing  (test-only fakes/fixtures; depends on :core only)
```

Rules enforced by convention (and `dependencies` blocks):

- `:core` depends on **nothing** but the Kotlin stdlib + JDK. It must stay
  usable from any JVM language/tool that needs honest storage numbers.
- Providers (`:filesystem`, `:sqlite`) depend on `:core` (and, for `:sqlite`,
  on `:database-api`) and expose their engine dependency **internally**
  (`implementation`), so consumers of the SPI never transitively adopt an
  engine.
- `:history` depends only on `:core`.
- `:reactive` adds exactly one dependency to the stack: **kotlinx-coroutines**
  (an `api` dependency — `StateFlow`/`RerunState` are public API). It is
  engine-agnostic: it orchestrates *any* `StorageAnalyzer`, not a specific
  provider.
- `:room` **composes** existing layers (`:core`, `:sqlite`, `:reactive`) and
  reads Room's *exported schema bundle* JSON instead of touching androidx
  artifacts — Room's annotations are binary-retention and would be invisible
  to reflection anyway. Its JSON parser (`kotlinx-serialization-json`, DOM
  only, `implementation`) is internal. The SQLite JDBC driver comes
  transitively from `:sqlite` at runtime scope — nothing is re-bundled.
- `:testing` is never used by production modules except in tests.

## Core packages (quick map)

| Package                            | Contents                                                             |
|------------------------------------|----------------------------------------------------------------------|
| `core.measurement`                 | `ByteCount` (non-negative, checked arithmetic), `MetricType` + `MetricDimension`, `MeasurementState`, `MeasurementAccuracy`, `MeasurementMethod`, `SingleMeasurement`, `StorageUnits` |
| `core.identity`                    | `ResourceIdentity`, `ProviderId`, `StorageSystemId`, `ResourceKind`, `PathIdentity` (canonical cross-provider file scheme) |
| `core.resource`                    | `StorageResource`, `StorageCategory`, `StorageScope`, `OwnerRef`, `ReferenceLink`, `RelationType` |
| `core.config`                      | `AnalyzerConfig`, `AnalysisOptions`, `AnalysisBudget`, `AnalysisContext` / `BudgetedAnalysisContext`, progress events |
| `core.provider`                    | `StorageProvider` SPI + registry (`discoverResources`, `measure`, `related`, `healthCheck`, capabilities) |
| `core.capability`                  | `StorageCapability` + `Capabilities` (probed, honest, per-system) |
| `core.policy`                      | `AccountingPolicy` implementations; allocation is separate from measurement |
| `core.analytics`                   | `InventoryComposer` (deduplication and alias collapse), `GlobalReportBuilder` |
| `core.entity` (+ `entity.spi`)     | descriptors, relations, `EntityAnalyzer` (exclusive/inclusive views), record/entity SPIs |
| `core.result`                      | `StorageMeasurement`, `GlobalReport`, `EntityReport`, `RecordBreakdown`, diagnostics & errors |
| `core.snapshot`                    | `StorageSnapshot` (summary, never an estimate)                        |
| `core.cache`                       | measurement cache abstraction (incremental runs)                      |
| `core` (root package)              | `StorageAnalyzer` facade                                              |

## Runtime data flow (global analysis)

```
StorageAnalyzer.analyzeGlobal()
   → InventoryComposer.compose()      per-provider streaming discovery,
                                      stableKey dedup + alias collapse
   → measure() of unique resources    honest UNAVAILABLE from providers,
                                      budget-checked per consumption step
   → attribution policies             only on measured bytes; deterministic
   → reconciliation checks            explicit differences, verdicts
   → GlobalReport                     totals, breakdowns, diagnostics, completeness
```

Snapshot capture (`captureSnapshot`) reuses exactly the same pipeline and
packs a `StorageSnapshot`; persistence is deliberately outside `:core` —
`:history` ships append-only stores and deterministic comparisons.

## Provider contract (SPI)

Providers implement `StorageProvider`:

- `discoverResources(systemId?, ctx): Sequence<StorageResource>` — lazy,
  streaming; must honor `ctx.ensureActive()` / budgets.
- `measure(identity, metrics, ctx): StorageMeasurement` — per-resource;
  every requested metric is answered (MEASURED / UNAVAILABLE / NOT_APPLICABLE)
  with provenance; providers may not silently drop or zero values.
- `related(identity, ctx): List<ReferenceLink>` — adjacency traversal
  (directories → children, FK relations → record resources).
- `capabilities()` / `capabilitiesFor(system)` — probed feature sets with
  explicit reasons for the unsupported ones.
- `healthCheck(ctx)` — read-only diagnostics (e.g. `integrity_check` for
  SQLite; missing scan roots for filesystems).
- Optional hooks: `recordAnalytics()` (field-level record engine),
  `entityResolver()` (instance-level resource resolver), `discoverSystems()`.

### Identity schemes

1. **Filesystem / PathIdentity scheme** — `providerId="filesystem"`,
   `systemId` = canonical absolute path of the *parent directory*,
   `nativeId` = base name, `namespace = null`. Providers that touch physical
   files (SQLite sidecars, database containers) declare `alsoDiscoverableBy`
   aliases in this exact scheme so the global composer can collapse
   double discoveries into one unique resource.
2. **SQLite scheme** — `systemId` = canonical parent dir of the database file
   (or `memory#<n>`); `namespace` = encoded database base name; `nativeId`s:
   `db`, `t:<enc>`, `i:<enc>`, `r:<table>:<rowid>` or
   `r:<table>:p:<n>:<pkkey>` with `\u001F`-joined pk parts (count-prefixed,
   percent-encoded). Encoding lives in `SQLiteResourceIds` and survives
   arbitrary table names.

## Threading & lifecycle

- The analyzer drives providers **sequentially**; providers may be called
  from multiple threads only if their implementation guarantees it
  (the shipped ones are single-threaded-by-convention with documented
  exceptions: `InMemorySnapshotStore` is synchronized; `FileSnapshotStore`
  appends are locked).
- Everything is `AutoCloseable`; the facade closes providers in reverse
  registration order.
- Cancellation: `AnalysisContext` provides `ensureActive()` (throws
  `StorageError.Cancelled`) and budget steps (`consumeResource`,
  `consumeEdge`) — all providers interleave them so bounded runs produce
  honest `PARTIAL` completeness with diagnostics.

## Extension points for new technologies

1. Implement `StorageProvider` (small, 7 methods) with honest capabilities.
2. If records/entities are supported, implement `RecordAnalyticsEngine`
   (field-level breakdowns) and/or `EntityResourceResolver` (instance
   enumeration) and register with the facade (`registerEntity`).
3. Reuse `PathIdentity` for anything physically file-backed to get free
   global dedup with the filesystem provider.
4. Provide a `defaultMetricTypes()` set: the metrics a provider can measure
   on its default resources when a run leaves the query set empty.
5. Test with `:testing` (`RecordingContext` etc.) — see `FilesystemProviderTest`
   for the complete template.
6. When the technology keeps rich metadata **elsewhere** (an exported schema,
   a manifest, a descriptor file), *compose* instead of reflecting over the
   engine: derive descriptors/mappings from that metadata and reuse the
   existing providers underneath. The shipped reference is `:room`, which
   turns Room's exported schema bundle into descriptors on top of `:sqlite`
   without a single androidx dependency.
