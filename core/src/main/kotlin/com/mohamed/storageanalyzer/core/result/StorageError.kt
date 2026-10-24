package com.mohamed.storageanalyzer.core.result

/**
 * Typed errors of the storage analytics engine.
 *
 * Two usage modes:
 * 1. **Result carrying** — analysis code converts failures into
 *    [StorageDiagnostic]s / per-resource error entries, so partial results
 *    survive.
 * 2. **Control flow** — cancellation (and only cancellation) is thrown as
 *    [Cancelled] from `AnalysisContext.ensureActive()` so provider code stops
 *    promptly.
 */
sealed class StorageError(message: String) : RuntimeException(message) {

    /** Provider-level failure (e.g. driver missing, connection refused). */
    class ProviderUnavailable(val providerId: String, val reason: String) :
        StorageError("provider '$providerId' unavailable: $reason")

    /** Per-resource failure; the resource is reported as unmeasured. */
    class MeasurementUnavailable(
        val resourceKey: String,
        val metricId: String?,
        val reason: String,
    ) : StorageError("measurement of '$resourceKey'" + (metricId?.let { " (metric $it)" } ?: "") + " unavailable: $reason")

    class PermissionDenied(val resourceKey: String, val reason: String) :
        StorageError("permission denied for '$resourceKey': $reason")

    class UnsupportedCapability(val capabilityId: String, val reason: String) :
        StorageError("capability '$capabilityId' is unsupported: $reason")

    /** Only thrown for cancellation; analysis aborts with honest diagnostics. */
    class Cancelled(reason: String? = null) : StorageError("analysis cancelled" + (reason?.let { ": $it" } ?: ""))

    /** Programmer/API misuse (bad registrations, invalid descriptor, ...). */
    class InvalidOperation(message: String) : StorageError(message)

    /** Identity collision that cannot be resolved (e.g. two providers claiming different kinds). */
    class IdentityConflict(val key: String, details: String) : StorageError("identity conflict for '$key': $details")

    /** Internal inconsistency — always a bug; wrap the original exception. */
    class Internal(message: String, cause: Throwable?) :
        StorageError(message + (cause?.let { " (cause: ${it.message ?: it.javaClass.simpleName})" } ?: ""))
}
