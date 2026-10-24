# API reference

Storage Analyzer is a Kotlin/JVM library. This page maps the public surface;
KDoc in the source is authoritative for signatures and defaults. Published
artifacts carry `-sources` and `-javadoc` (Dokka) jars.

Package names are always `com.mohamed.storageanalyzer.*`; Maven coordinates
are `io.github.mohamed-dev-ui:storageanalyzer-<module>`.

## Entry point

`StorageAnalyzer` ([source](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/StorageAnalyzer.kt))
owns the registered providers and exposes every analysis mode:

| Member | Returns | Purpose |
|---|---|---|
| `analyzeGlobal(options, context)` | `GlobalReport` | Full inventory, measurement, deduplication, attribution and reconciliation. |
| `captureSnapshot(options, context, includeResourceDetails)` | `StorageSnapshot` | One-call snapshot for history; persistence is caller-owned. |
| `analyzeResource(identity, options, context)` | `StorageMeasurement` | Measure one resource through its owning provider. |
| `analyzeEntity(descriptor, instanceKey, options, context)` | `EntityReport` | Single entity instance (inclusive/exclusive views). |
| `analyzeEntityType(descriptor, options, context)` | `EntityReport` | Aggregate every instance of one descriptor. |
| `runDiagnostics(context)` | `List<StorageDiagnostic>` | Provider health checks plus registration checks. |
| `register(provider)`, `registerEntity(descriptor)` | — | Register providers/descriptors after construction. |
| `providers()`, `registeredEntities()` | lists | Inspect current registrations. |
| `close()` | — | Closes providers in reverse registration order. |

Configuration lives in `AnalyzerConfig`, `AnalysisOptions` and
`AnalysisBudget` (see [config source](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/config/)).

## Core model

| Type | Meaning | Source |
|---|---|---|
| `ByteCount` | Non-negative byte quantity with checked arithmetic; the only size unit. | [measurement/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/measurement/) |
| `MetricType`, `MetricDimension` | What is measured; dimensions keep logical/allocated/capacity values apart. | [MetricType.kt](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/measurement/MetricType.kt) |
| `SingleMeasurement`, `MeasurementState` | One metric answer: `MEASURED` / `UNAVAILABLE` (+reason) / `NOT_APPLICABLE`. | [SingleMeasurement.kt](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/measurement/SingleMeasurement.kt) |
| `MeasurementProvenance`, `MeasurementMethod`, `MeasurementAccuracy` | How a value was obtained: method id, accuracy class, timestamp, backend, limitations, warnings. | [measurement/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/measurement/) |
| `StorageUnits` | IEC formatting helpers (e.g. `formatIec`). | [measurement/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/measurement/) |
| `ResourceIdentity`, `PathIdentity` | Stable resource keys; `PathIdentity` is the canonical file-backed scheme. | [identity/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/identity/) |
| `ProviderId`, `StorageSystemId`, `ResourceKind` | Provider/system/kind identifiers. | [Identifiers.kt](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/identity/Identifiers.kt), [ResourceKind.kt](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/identity/ResourceKind.kt) |
| `StorageResource`, `StorageCategory`, `StorageScope`, `ReferenceLink` | Discovered units, classification, scope and adjacency. | [resource/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/resource/) |
| `StorageMeasurement`, `GlobalReport`, `EntityReport` | Provider-level, global and entity-level results. | [result/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/result/) |
| `StorageTotals`, `StorageBreakdown`, `RecordBreakdown` | Aggregate and breakdown views. | [result/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/result/) |
| `StorageDiagnostic`, `StorageError` | Diagnostics and typed failures (`Cancelled`, `ProviderUnavailable`, `IdentityConflict`). | [result/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/result/) |
| `StorageSnapshot` | Point-in-time summary for history and comparison. | [StorageSnapshot.kt](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/snapshot/StorageSnapshot.kt) |
| `AccountingPolicy`, `AccountingPolicies` | Shared-resource attribution (`count_once_globally` is the default). | [policy/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/policy/) |
| `MeasurementCache`, `NullMeasurementCache` | Optional measurement cache SPI; no caching by default. | [cache/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/cache/) |
| `EntityDescriptor`, `EntityRelation`, `EntityAnalyzer`, `RelationWalker` | Entity analytics and relation traversal. | [entity/](../core/src/main/kotlin/com/mohamed/storageanalyzer/core/entity/) |

## Providers and SPIs

| Type | Module | Purpose |
|---|---|---|
| `StorageProvider`, `StorageProviderRegistry` | `:core` | Provider SPI (discovery, measurement, relations, capabilities, health) and registry. |
| `Capabilities`, `StorageCapability` | `:core` | Probed feature support with explicit unsupported reasons. |
| `RecordAnalyticsEngine`, `EntityResourceResolver` | `:core` (`entity.spi`) | Optional field-level record analytics and instance enumeration. |
| `DatabaseIntrospector`, `DatabasePageStats`, `TableDescriptor`, `IndexDescriptor` | `:database-api` | Provider-agnostic relational introspection contracts. |
| `FilesystemProvider`, `VolumeStats` | `:filesystem` | Read-only `java.nio` provider; host adapters for allocation units (`allocationUnitResolver`) and volume capacity (`volumeStatsResolver`). |
| `SQLiteStorageProvider`, `SQLiteDatabaseSpec` | `:sqlite` | Read-only SQLite provider over JDBC. |
| `SQLiteIntrospection`, `SQLiteRecordFormat`, `SQLiteResourceIds` | `:sqlite` | Introspection, record-format accounting and stable identity encoding. |
| `entityDescriptorForTable` | `:sqlite` | Descriptor helper for relational tables. |

## History

| Type | Purpose |
|---|---|
| `SnapshotStore` | Append-only storage contract (`append`, `readAll`, `latest`, `size`). |
| `FileSnapshotStore`, `InMemorySnapshotStore` | Shipped implementations; files fail loudly on corruption. |
| `SnapshotFormat` | Versioned, strictly decoded on-disk text format. |
| `SnapshotComparison.between(from, to)` | Signed deltas, per-metric/category breakdowns, optional attribution, explicit not-comparable metrics. |
| `GrowthModelFactory.fit(snapshots)` / `GrowthModel` | Ordinary-least-squares projection with reported r²; `ESTIMATED` accuracy. |

Sources: [history/](../history/src/main/kotlin/com/mohamed/storageanalyzer/history/).

## Reactive

| Type | Purpose |
|---|---|
| `ReactiveAnalyzer(analyzer, scope, policy, options, dispatcher)` | Wraps any `StorageAnalyzer` in a hot pipeline; `state`/`results` are `StateFlow`s; `refreshNow()` runs a cold turn. |
| `RefreshPolicy.Every`, `RefreshPolicy.OnChange`, `RefreshPolicy.Manual` | Polling cadence, fingerprint-driven refresh, or explicit refreshes only. |
| `RerunState.Idle/Running/Completed/Failed` | Lifecycle states; failures and in-flight turns keep the previous result visible. |
| `AnalysisDigest` | Deterministic content key used for emission suppression. |
| `StorageAnalyzer.coldReportFlow(options)` | One fresh analysis per collector, then completion. |

Sources: [reactive/](../reactive/src/main/kotlin/com/mohamed/storageanalyzer/reactive/).

## Room

| Type | Purpose |
|---|---|
| `RoomSchemaBundle.parse(path/json)` | Parses Room's exported schema bundle (`exportSchema = true`). |
| `RoomDatabaseAnalyzer(spec, bundle, config)` | Cold analysis: `analyzeOnce()`, `fingerprint()`, `defaultOptions()`. |
| `RoomAnalysisConfig` | `includeRowScan`, budget, probe timeout, embedded global report. |
| `RoomStorageMonitor(spec, bundle, scope, pollInterval, …)` | Hot monitor with `state`/`results`, digest suppression and manual refresh. |
| `RoomEntitySource`, `SidecarRole`, `SidecarStorage`, `RoomEntityStorage` | Result model for entities, sidecars and drift. |

Sources: [room/](../room/src/main/kotlin/com/mohamed/storageanalyzer/room/).

## Testing

`io.github.mohamed-dev-ui:storageanalyzer-testing` (use as
`testImplementation`) provides:

- `RecordingContext` — scriptable budget/cancellation context that records
  diagnostics and progress events.
- `TempDir` — self-deleting temporary fixture directory.
- `testIdentity(...)` — identity builder for provider tests.

Source: [testing/](../testing/src/main/kotlin/com/mohamed/storageanalyzer/testing/).

## Stability and versioning

- The project is **pre-1.0**. Public APIs are documented and reviewed, but a
  minor release may include a breaking change when necessary. Every such
  change is recorded in [CHANGELOG.md](../CHANGELOG.md) with a migration note.
- New **default** methods may be added to provider SPIs in a minor release;
  implementations compiled against an older version keep working unless the
  changelog says otherwise.
- Built-in `MetricType` and `MeasurementMethod` ids are frozen. Custom ids
  must be namespaced (`x_<provider>.<name>`) and must not collide with
  built-ins.
- The snapshot on-disk format is versioned; readers reject unknown versions
  loudly. See [COMPATIBILITY.md](COMPATIBILITY.md) and
  [RELEASE.md](RELEASE.md).
