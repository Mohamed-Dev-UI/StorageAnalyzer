# Storage Analyzer documentation

Current release: `0.1.0` (first tag; published to Maven Central by CI — see
[RELEASE.md](RELEASE.md)). The [README](../README.md) is the front door; this
directory is the reference documentation.

## Start here

| If you want to… | Read |
|---|---|
| Get a first report working | [README quickstart](../README.md#quickstart), then [USAGE.md](USAGE.md) Scenario A |
| Pick the right modules | [README module map](../README.md#modules), [COMPATIBILITY.md](COMPATIBILITY.md) |
| Understand what the numbers mean | [MEASUREMENT_ACCURACY.md](MEASUREMENT_ACCURACY.md), [ACCOUNTING.md](ACCOUNTING.md) |
| Integrate a specific feature | [USAGE.md](USAGE.md) scenarios A–L, [API.md](API.md) |
| Write a storage provider | [ARCHITECTURE.md](ARCHITECTURE.md), [API.md](API.md), [USAGE.md](USAGE.md) Scenario J |
| Decide whether the library fits | [LIMITATIONS.md](LIMITATIONS.md), [COMPATIBILITY.md](COMPATIBILITY.md), [PERFORMANCE.md](PERFORMANCE.md) |
| Cut a release | [RELEASE.md](RELEASE.md) |
| Contribute | [CONTRIBUTING.md](../CONTRIBUTING.md), [CHANGELOG.md](../CHANGELOG.md), [SECURITY.md](../SECURITY.md) |

## Reference index

| Document | Contents |
|---|---|
| [USAGE.md](USAGE.md) | Scenario cookbook (A–L), import modes, error model, troubleshooting, FAQ. Scenarios A–G and J–L are executed by `UsageExamplesTest`; H and I are backed by unit tests. |
| [API.md](API.md) | Public API map: facade, model types, providers, history, reactive, Room, testing and SPIs. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module graph, dependency rules, core packages, data flow, identity schemes, extension points. |
| [MEASUREMENT_ACCURACY.md](MEASUREMENT_ACCURACY.md) | Accuracy ladder, never-zero guarantee, per-provider accuracy matrix, provenance fields. |
| [ACCOUNTING.md](ACCOUNTING.md) | Unique-footprint semantics, deduplication, shared-resource attribution, reconciliation. |
| [COMPATIBILITY.md](COMPATIBILITY.md) | JVM/Kotlin/toolchain support, provider/platform matrix, binary-compatibility policy. |
| [LIMITATIONS.md](LIMITATIONS.md) | Honest per-module limitations and the deferred-module roadmap. |
| [PERFORMANCE.md](PERFORMANCE.md) | Measured benchmark numbers, reproduction command and caveats. |
| [RELEASE.md](RELEASE.md) | Versioning, Maven Central setup, release checklist, rollback policy. |

Repository-level policies: [CHANGELOG.md](../CHANGELOG.md),
[CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md), and
[LICENSE](../LICENSE).

## Documentation guarantees

- **Snippets are executable.** `UsageExamplesTest` runs scenarios A–G and J–L
  in `USAGE.md` against real fixtures as part of `./gradlew build`; scenarios
  H and I are exercised by module unit tests.
- **Claims are testable.** Accuracy and accounting statements map to unit
  tests in `:core`, `:filesystem`, `:sqlite`, `:history`, `:reactive` and
  `:room`.
- **Gaps are documented.** If a metric is unavailable, an API is not shipped
  or a platform is not supported, it is listed in
  [LIMITATIONS.md](LIMITATIONS.md) or [COMPATIBILITY.md](COMPATIBILITY.md)
  instead of being left implicit.

## Internal records

Engineering design records and implementation reports are kept for provenance
in [internal/](internal/). They are not user documentation, may reference an
internal specification's section numbers, and can be stale; the public docs
above are authoritative.
