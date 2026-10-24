# Accounting

How the library ensures that measured bytes are counted **exactly once**,
how shared resources are attributed, and how the two independent views
(provider-view vs. global view) stay reconcilable.

## Three distinct summaries

1. **Provider view (`StorageMeasurement`)** — what one provider sees for one
   resource. Contains raw measurements and unavailability reasons. Includes
   provider-side duplicates freely (e.g. a SQLite database and its WAL
   sidecar are two separate resources in this view).
2. **Entity view (`EntityReport`)** — per-instance/per-descriptor accounting
   driven by descriptors and relation walking. Inclusive sizes
   (entity + related) and exclusive sizes (uniquely attributed to the entity)
   are defined separately and never conflated.
3. **Global view (`GlobalReport`)** — deduplicated inventory over all
   providers with policy-based attribution of shared resources. **The global
   uniqueFootprint is the only "storage usage" number the library blesses for
   cross-provider totals.**

## Unique-footprint semantics

- Every `StorageResource` yields a stable key (`identity.stableKey`).
- The inventory keeps the **first** provider's entry for a key (providers are
  visited in deterministic registration order) and records later same-key
  discoveries as overlaps.
- Providers **cross-register aliases**: a resource that can also be
  discovered under a different identity may declare `alsoDiscoverableBy`
  identities. When such an alias matches an earlier entry (e.g. the
  filesystem provider already reported the physical `.sqlite` container that
  the SQLite provider is about to report as a database), the later entry is
  *excluded from unique totals* and reported in
  `GlobalReport.overlapMeasurements`.
- Directories are structural, not byte holders — exactly their children are
  measured; the scanned root doubles as the **volume facade** for capacity
  metrics.

## Shared resources and attribution

A resource is "shared" when more than one owner claims it (e.g. hard links, a
record reached through two relations, or the same table named by several
entities). Policies only distribute **already-measured** bytes:

| Policy                | Semantics                                                            |
|-----------------------|----------------------------------------------------------------------|
| `count_once_globally` (default) | the physical bytes appear once in unique totals; attribution of *owners* is not forced |
| `canonical_owner`     | all bytes attributed to the canonical owner (stable order)            |
| `equal_split`         | `ByteCount.splitEqually` — equal shares, remainder distributed in a stable order |
| `proportional`        | shares proportional to a documented basis; sum equals total           |

All policies guarantee conservation: allocated per-owner shares plus
explicitly unattributed remainder == resource bytes (checked arithmetic via
`ByteCount`).

## Hard links / resource decomposition

- `StorageResource.decomposes: ResourceIdentity?` — when set, the resource's
  footprint is excluded from unique sums (its bytes already belong to the
  resource it decomposes to). The filesystem provider sets this on the
  **second spotted path** of a hard-linked inode (first-seen path keeps the
  bytes) whenever the JVM exposes `fileKey`.
- Inside the entity layer the same field expresses "this table's row storage
  is already contained in X" (e.g. virtual tables mapping to real ones).
- `GlobalReport` always distinguishes *unique footprint* (after dedup/alias
  collapse; policy-aware) from *provider-view sums*.

## Exclusive vs. inclusive entity views

- `EXCLUSIVE`: only bytes whose measurement is attributed to the entity —
  for relational tables, the table's own B-tree/storage minus shared/index
  accounting as partitioned by the reconciliation checks.
- `INCLUSIVE`: entity + everything it references along `relations`
  (per-row record storage addition, junction tables, etc.). The relation
  walker guarantees termination and budget compliance.

## Reconciliation

`GlobalReport.reconciliation: List<ReconciliationDifference>` carries explicit,
machine-readable checks. The shipped global check is:

- `attribution_conservation` — the identity
  `uniqueFootprint == exclusiveAttributed + attributedShared + sharedOnly + unattributed`
  for the footprint metric. Any residue equals the footprint metric of
  resources whose measurement was unmeasured in this run (surfaced via
  `unmeasuredResources`), so the gap is *explained*, not hidden.

Provider-level accounting differences (e.g. SQLite page-reconciliation
between dbstat B-tree sums, freelist and page totals, or filesystem
heuristic mismatches) surface as **provider diagnostics** attached to the
measurements, not absorbed into totals. Nothing is "adjusted away" behind
the scenes, and consumers can render every difference.

## Overflow & integrity

`ByteCount` enforces non-negativity and raises on overflow via
`Math.addExact`-style checked ops — aggregation code paths contain no silent
wrap-around. Negative deltas (shrinking storage between snapshots) are
signed longs at the comparison layer, but always map to non-negative byte
counts in any stored snapshot/report.
