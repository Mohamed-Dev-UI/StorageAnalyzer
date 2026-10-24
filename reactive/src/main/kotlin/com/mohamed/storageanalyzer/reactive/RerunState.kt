package com.mohamed.storageanalyzer.reactive

import java.time.Instant

/**
 * Lifecycle state of one monitored analysis pipeline.
 *
 * The state is always honest: a failure keeps the last completed value
 * visible through [Failed.previous], a refresh in progress keeps it visible
 * through [Running.previous], and nothing is ever silently dropped.
 *
 * @param T the value type produced by one analysis turn.
 */
public sealed interface RerunState<out T> {

    /** When this state was observed. */
    public val at: Instant

    /** Nothing has been computed yet (before the first turn or after reset). */
    public data class Idle(
        override val at: Instant = Instant.now(),
    ) : RerunState<Nothing>

    /**
     * A turn is currently being computed. Consumers keep rendering
     * [previous] until the turn completes — stale data is explicit.
     */
    public data class Running<T>(
        override val at: Instant = Instant.now(),
        public val previous: Completed<T>?,
    ) : RerunState<T>

    /** One turn finished successfully. */
    public data class Completed<T>(
        override val at: Instant = Instant.now(),
        public val value: T,
        /**
         * Deterministic change key of [value]: equal digests mean equal
         * content. Consumers can key their UI on it; the pipeline itself
         * suppresses result emissions when the digest is unchanged.
         */
        public val digest: String,
        public val durationMs: Long,
        public val previous: Completed<T>?,
    ) : RerunState<T>

    /**
     * One turn failed. Analysis errors are state, not crashes: the next
     * turn simply retries. [CancellationException] does NOT end up here —
     * cancellation is handled cooperatively.
     */
    public data class Failed<T>(
        override val at: Instant = Instant.now(),
        public val error: Throwable,
        public val previous: Completed<T>?,
    ) : RerunState<T>
}
