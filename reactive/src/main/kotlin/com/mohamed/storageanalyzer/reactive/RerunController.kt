package com.mohamed.storageanalyzer.reactive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import java.time.Duration
import java.time.Instant

/**
 * Engine-agnostic rerun orchestrator: owns one analysis
 * pipeline, runs its turn loop according to a [RefreshPolicy], publishes the
 * lifecycle in [state] and the completed values in [results].
 *
 * Guarantees:
 * - turns are serialized (a mutex; overlapping triggers wait, never race);
 * - blocking `produce` work is executed on [dispatcher] (default IO) so the
 *   collecting thread is never blocked;
 * - a failing turn becomes [RerunState.Failed] and the loop continues;
 *   [CancellationException] propagates and preserves the last known state;
 * - [results] only re-emits when the digest changes (unless
 *   [emitOnlyOnDigestChange] = false); equal digests mean equal content;
 * - `close()` cancels only this controller's jobs — it never cancels the
 *   user's scope and never closes any user resource.
 *
 * The controller is a building block; for the common `StorageAnalyzer`
 * use case prefer the [ReactiveAnalyzer] convenience wrapper.
 */
public class RerunController<T : Any>(
    /** Caller-owned scope in which the loop and manual refreshes run. */
    private val userScope: CoroutineScope,
    public val policy: RefreshPolicy,
    /** Dispatcher for the (usually blocking) analysis turn. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** One analysis turn; may block and may throw on hard failure. */
    private val produce: suspend () -> T,
    /** Deterministic change key of a produced value. */
    private val digestOf: (T) -> String,
    public val emitOnlyOnDigestChange: Boolean = true,
) : AutoCloseable {

    private val mutex = Mutex()

    private val mutableState = MutableStateFlow<RerunState<T>>(RerunState.Idle())
    public val state: StateFlow<RerunState<T>> = mutableState.asStateFlow()

    private val mutableResults = MutableStateFlow<T?>(null)
    public val results: StateFlow<T?> = mutableResults.asStateFlow()

    private var lastCompleted: RerunState.Completed<T>? = null
    private var lastPublishedDigest: String? = null
    private var loopJob: Job? = null

    /**
     * Starts the automatic turn loop. Idempotent; no-op under
     * [RefreshPolicy.Manual] (explicit refresh only).
     */
    public fun start() {
        if (policy is RefreshPolicy.Manual) return
        synchronized(this) {
            if (loopJob != null) return
            loopJob = userScope.launch { runLoop() }
        }
    }

    /** Fire-and-forget refresh; intended for external signal hooks. */
    public fun refresh(trigger: String? = null) {
        userScope.launch { refreshNow(trigger) }
    }

    /**
     * Runs exactly one analysis turn: updates [state], publishes into
     * [results] when the digest changed, and returns the resulting state.
     * This is the *cold* single-shot entry point — it works with or without
     * a running loop.
     */
    public suspend fun refreshNow(trigger: String? = null): RerunState<T> {
        mutex.withLock {
            mutableState.value = RerunState.Running(previous = lastCompleted)
            try {
                val startNanos = System.nanoTime()
                val value = withContext(dispatcher) { produce() }
                val digest = digestOf(value)
                val completed = RerunState.Completed(
                    at = Instant.now(),
                    value = value,
                    digest = digest,
                    durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                    previous = lastCompleted,
                )
                lastCompleted = completed
                mutableState.value = completed
                if (!emitOnlyOnDigestChange || digest != lastPublishedDigest) {
                    mutableResults.value = value
                    lastPublishedDigest = digest
                }
                return completed
            } catch (e: CancellationException) {
                // Cooperative stop: not a failure, keep the last honest state.
                mutableState.value = lastCompleted ?: RerunState.Idle()
                throw e
            } catch (e: Throwable) {
                val failed = RerunState.Failed(at = Instant.now(), error = e, previous = lastCompleted)
                mutableState.value = failed
                return failed
            }
        }
    }

    private suspend fun runLoop() {
        when (val p = policy) {
            is RefreshPolicy.Every -> {
                while (coroutineContext.isActive) {
                    refreshNow()
                    delay(p.interval.toMillis())
                }
            }

            is RefreshPolicy.OnChange -> {
                var fingerprint: String? = null
                var haveFirstTurn = false
                while (coroutineContext.isActive) {
                    val probed = runCatching { p.probe() }.getOrNull()
                    if (!haveFirstTurn || probed == null || probed != fingerprint) {
                        refreshNow()
                        haveFirstTurn = true
                    }
                    fingerprint = probed
                    delay(p.pollInterval.toMillis())
                }
            }

            RefreshPolicy.Manual -> return
        }
    }

    override fun close() {
        synchronized(this) {
            loopJob?.cancel()
            loopJob = null
        }
    }
}
