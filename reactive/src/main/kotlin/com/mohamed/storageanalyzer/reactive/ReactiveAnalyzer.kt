package com.mohamed.storageanalyzer.reactive

import com.mohamed.storageanalyzer.core.StorageAnalyzer
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.result.GlobalReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Convenience wrapper that turns any [StorageAnalyzer] into a hot monitoring
 * pipeline: [state] exposes the full lifecycle, [results] publishes reports
 * (re-emitted only when content actually changed). The analyzer itself is
 * owned by the caller and is never closed by [close][AutoCloseable.close].
 */
public class ReactiveAnalyzer(
    private val analyzer: StorageAnalyzer,
    scope: CoroutineScope,
    public val policy: RefreshPolicy = RefreshPolicy.Every(Duration.ofSeconds(30)),
    public val options: AnalysisOptions = AnalysisOptions.DEFAULT,
    /** Dispatcher for the (usually blocking) analysis turn; default at production. */
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val controller = RerunController<GlobalReport>(
        userScope = scope,
        policy = policy,
        dispatcher = dispatcher,
        produce = { analyzer.analyzeGlobal(options) },
        digestOf = { AnalysisDigest.of(it).value },
    )

    public val state: StateFlow<RerunState<GlobalReport>> get() = controller.state

    public val results: StateFlow<GlobalReport?> get() = controller.results

    /** Starts the automatic turn loop (idempotent; no-op under [RefreshPolicy.Manual]). */
    public fun start(): Unit = controller.start()

    /** Fire-and-forget refresh, e.g. from a watcher or invalidation callback. */
    public fun refresh(trigger: String? = null): Unit = controller.refresh(trigger)

    /** Cold single-shot refresh that also updates the hot state. */
    public suspend fun refreshNow(trigger: String? = null): RerunState<GlobalReport> =
        controller.refreshNow(trigger)

    /** Stops this monitor's jobs. The caller's analyzer and scope stay untouched. */
    override fun close(): Unit = controller.close()
}

/**
 * Cold flow semantics: every new collector runs one fresh analysis and then
 * completes. No shared state, no background loop.
 */
public fun StorageAnalyzer.coldReportFlow(
    options: AnalysisOptions = AnalysisOptions.DEFAULT,
): Flow<GlobalReport> = flow {
    emit(withContext(Dispatchers.IO) { analyzeGlobal(options) })
}
