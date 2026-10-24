package com.mohamed.storageanalyzer.reactive

import com.mohamed.storageanalyzer.core.result.GlobalReport

/**
 * Deterministic change key of a [GlobalReport].
 *
 * GlobalReport instances must NOT be compared directly for equality: they
 * carry options (with lambda fields), timestamps and durations. The digest
 * instead compares exactly what changes *content*:
 *
 * - completeness label,
 * - measured resource count,
 * - per-metric totals,
 * - the unique footprint,
 * - every resource's measured metric values keyed by its stable identity.
 *
 * Two reports with the same digest show the same storage content; UIs keyed
 * on the digest never re-render for identical data.
 */
public class AnalysisDigest private constructor(
    /** The exact, deterministic representation (the identity of the digest). */
    public val value: String,
) {

    override fun equals(other: Any?): Boolean = other is AnalysisDigest && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    public companion object {

        /** Builds the digest of one report. */
        public fun of(report: GlobalReport): AnalysisDigest = AnalysisDigest(
            buildString {
                append("completeness=").append(report.completeness.name)
                append("|resources=").append(report.resourceCount)
                append("|unique=").append(report.totals.uniqueFootprint.value)
                append("|metrics=")
                report.totals.byMetric.entries
                    .sortedBy { it.key.id }
                    .joinTo(this, ",") { it.key.id + ":" + it.value.value }
                append("|stash=v1")
                append("|r=")
                report.resourceSummaries
                    .sortedBy { it.identity.stableKey }
                    .joinTo(this, ";") { s ->
                        s.identity.stableKey + "=" +
                            s.footPrintMetrics.entries
                                .sortedBy { it.key.id }
                                .joinToString(",") { e -> e.key.id + ":" + e.value.value }
                    }
            },
        )
    }
}
