# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the project is pre-1.0, minor releases may contain breaking changes.
Every breaking change is called out here with a migration note. The release
process is described in [docs/RELEASE.md](docs/RELEASE.md).

## [Unreleased]

Target release: `0.1.0`. Nothing has been published to Maven Central yet.

### Added

- `:core` — measurement model with full provenance (`ByteCount`, `MetricType`,
  `SingleMeasurement`, `MeasurementAccuracy`), resource identity
  (`ResourceIdentity`, `PathIdentity`), provider SPI, capability probing,
  accounting policies, deduplicating inventory composition, entity analytics,
  snapshots, diagnostics and the `StorageAnalyzer` facade.
- `:filesystem` — read-only `java.nio` provider: streaming discovery, category
  classification, hard-link dedup, host adapters for allocation units and
  volume capacity (`VolumeStats`, e.g. Android `StatFs`), health checks.
- `:sqlite` — read-only SQLite analytics over JDBC: schema/page/record-level
  sizes, freelist accounting, WAL sidecars, `integrity_check`, page
  reconciliation and record streaming.
- `:database-api` — provider-agnostic relational contracts
  (`DatabaseIntrospector`, table/index/page descriptors).
- `:history` — versioned snapshot format, append-only file store, signed-delta
  comparisons and ordinary-least-squares growth fitting.
- `:reactive` — hot/cold analysis orchestration: digest-based change
  detection, refresh policies, `StateFlow` lifecycle and cooperative
  cancellation.
- `:room` — Room schema-bundle-driven entity descriptors, cold one-shot
  analysis, drift diagnostics and a change-detecting hot monitor with no
  androidx dependency.
- `:testing` — `RecordingContext`, `TempDir` and `testIdentity` for provider
  tests.
- `:example` — runnable JVM demo and the executable documentation scenarios
  used by `UsageExamplesTest`.
- `:app` — Android showcase (Hilt, androidx Room + KSP, Compose Material 3)
  covering the Android-runnable library features with an in-app guide.
- Documentation set: usage cookbook, API map, architecture, accuracy,
  accounting, compatibility, limitations, performance, release process,
  contributing, security and this changelog.

### Known limitations

- No Maven Central release exists yet; use the composite build or
  `./gradlew publishToMavenLocal` until the first tag is published.
- `:sqlite` and `:room` require a SQLite JDBC driver (JVM only) and are
  intentionally not shipped in the Android app.
- See [docs/LIMITATIONS.md](docs/LIMITATIONS.md) for the complete,
  module-by-module list.
