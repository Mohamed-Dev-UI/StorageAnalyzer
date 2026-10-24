package com.mohamed.storageanalyzer.core.result

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity

/** Severity of a [StorageDiagnostic]. */
enum class DiagnosticSeverity { INFO, WARNING, ERROR }

/**
 * A diagnostic observation produced during analysis: permission problems,
 * identity conflicts, budget stops, consistency warnings, health-check
 * results. Diagnostics never throw; they produce honest, actionable results.
 */
data class StorageDiagnostic(
    /** Stable machine code, e.g. `"fs.file.disappeared_during_scan"`. */
    val code: String,

    val severity: DiagnosticSeverity,

    /** Human-readable description; must avoid echoing sensitive payloads. */
    val message: String,

    /** Resource the diagnostic refers to, when known. */
    val identity: ResourceIdentity? = null,

    /** Provider that produced it, when attributed. */
    val providerId: ProviderId? = null,

    /** Short suggested action for the developer, if one exists. */
    val suggestedAction: String? = null,
)
