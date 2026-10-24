package com.mohamed.storageanalyzer.reactive

import java.time.Duration

/**
 * When a [RerunController] schedules analysis turns.
 */
public sealed interface RefreshPolicy {

    /**
     * Re-analyze on a fixed cadence; the first turn runs immediately at
     * [RerunController.start]. A monitor that starts empty and waits a full
     * interval would be a trap, so there is no warm-up delay.
     */
    public data class Every(public val interval: Duration) : RefreshPolicy {
        init {
            require(!interval.isZero && !interval.isNegative) { "interval must be positive, was $interval" }
        }
    }

    /**
     * Re-analyze only when a cheap fingerprint probe returns a value that
     * differs from the previous one (first turn always runs).
     *
     * The probe must be *cheap* — in the Room integration it is three pragmas
     * plus the database file length, not a full analysis. A probe failure or
     * `null` means "unknown"; unknown triggers a run rather than hiding it.
     */
    public data class OnChange(
        public val probe: suspend () -> String?,
        public val pollInterval: Duration,
    ) : RefreshPolicy {
        init {
            require(!pollInterval.isZero && !pollInterval.isNegative) { "pollInterval must be positive, was $pollInterval" }
        }
    }

    /** No automatic refresh; callers drive with [RerunController.refreshNow] / [RerunController.refresh]. */
    public data object Manual : RefreshPolicy
}
