package com.mohamed.storageanalyzer.core.config

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageError
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe execution context of one analysis run: cancellation, progress
 * reporting and budget checks.
 *
 * Providers must call [ensureActive] at safe points and [report]/[diagnostic]
 * as documented so that partial results and honest budgets are honored.
 */
interface AnalysisContext {

    /** True when a cancel has been signaled. */
    fun isCancelled(): Boolean

    /**
     * Throws [StorageError.Cancelled] when a cancel is signaled; cheap to
     * call repeatedly at safe points.
     */
    fun ensureActive() {
        if (isCancelled()) throw StorageError.Cancelled()
    }

    /** Elapsed time of this run. */
    fun elapsed(): Duration

    /** Hard deadline of this run, when configured. */
    fun deadline(): Instant?

    /** Emits a progress event (implementations may throttle). */
    fun report(event: AnalysisProgress)

    /** Emits a diagnostic (safe from any thread). */
    fun diagnostic(diagnostic: StorageDiagnostic)

    companion object {
        /** Creates the default context with the given budget. */
        fun create(budget: AnalysisBudget = AnalysisBudget.UNBOUNDED): DefaultAnalysisContext =
            DefaultAnalysisContext(budget)
    }
}

/**
 * Internal-enriched context exposed to traversal machinery: budget-consume
 * hooks. Implementations must be thread-safe.
 */
interface BudgetedAnalysisContext : AnalysisContext {
    /** True while the time budget still allows work. */
    fun timeRemaining(): Boolean

    /** Consumes one resource slot; false when the resource budget is exhausted. */
    fun consumeResource(): Boolean

    /** Consumes one edge slot; false when the edge budget is exhausted. */
    fun consumeEdge(): Boolean
}

/**
 * Progress events are deliberately small (no payloads); throttling is up to
 * the emitting provider.
 */
sealed interface AnalysisProgress {

    /** A resource measurement completed. */
    data class ResourceMeasured(val identity: ResourceIdentity, val count: Long, val elapsed: Duration) : AnalysisProgress

    /** A logical phase started (providers define their phases; ids are stable strings). */
    data class PhaseStarted(val phaseId: String, val label: String?) : AnalysisProgress

    /** A logical phase completed. */
    data class PhaseCompleted(val phaseId: String, val count: Long) : AnalysisProgress

    /** Note for logs (e.g. dedup/exclusion information). */
    data class Note(val message: String) : AnalysisProgress
}

/** Default thread-safe context implementation. */
class DefaultAnalysisContext(private val budget: AnalysisBudget = AnalysisBudget.UNBOUNDED) : BudgetedAnalysisContext {

    private val cancelled = AtomicBoolean(false)
    private val startedAt: Instant = Instant.now()
    private val resourcesSeen = AtomicLong(0)
    private val edgesSeen = AtomicLong(0)
    private val eventsSeen = AtomicLong(0)

    @Volatile
    private var listener: ((AnalysisProgress) -> Unit)? = null

    @Volatile
    private var diagnosticListener: ((StorageDiagnostic) -> Unit)? = null

    override fun isCancelled(): Boolean = cancelled.get()

    override fun elapsed(): Duration = Duration.between(startedAt, Instant.now())

    override fun deadline(): Instant? = budget.time?.let { startedAt.plus(it) }

    /** Signals cancellation; in-flight runs stop at the next safe point. */
    fun cancel() {
        cancelled.set(true)
    }

    /** Observes progress events. Returns `this` for chaining. */
    fun onProgress(listener: (AnalysisProgress) -> Unit): DefaultAnalysisContext {
        this.listener = listener
        return this
    }

    /** Observes diagnostics. Returns `this` for chaining. */
    fun onDiagnostic(listener: (StorageDiagnostic) -> Unit): DefaultAnalysisContext {
        this.diagnosticListener = listener
        return this
    }

    override fun report(event: AnalysisProgress) {
        val l = listener ?: return
        val budgeted = budget.maxProgressEvents == null || eventsSeen.incrementAndGet() <= budget.maxProgressEvents!!
        if (budgeted) l(event)
    }

    override fun diagnostic(diagnostic: StorageDiagnostic) {
        diagnosticListener?.invoke(diagnostic)
    }

    /** Remaining resources before the resource budget stops. */
    fun remainingResources(): Long? = budget.maxResources?.let { max -> (max - resourcesSeen.get()).coerceAtLeast(0) }

    /** Consumes one resource-slot; false when the budget is exhausted. */
    override fun consumeResource(): Boolean {
        val max = budget.maxResources ?: return true
        return resourcesSeen.incrementAndGet() <= max
    }

    /** Remaining edges before the edge budget stops. */
    fun remainingEdges(): Long? = budget.maxEdgesTraversed?.let { max -> (max - edgesSeen.get()).coerceAtLeast(0) }

    /** Consumes one edge-slot; false when the edge budget is exhausted. */
    override fun consumeEdge(): Boolean {
        val max = budget.maxEdgesTraversed ?: return true
        return edgesSeen.incrementAndGet() <= max
    }

    /** True while the time budget still allows work. */
    override fun timeRemaining(): Boolean {
        val d = deadline() ?: return true
        return Instant.now().isBefore(d)
    }
}
