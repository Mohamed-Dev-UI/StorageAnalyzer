package com.mohamed.storageanalyzer.room

import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.reactive.RefreshPolicy
import com.mohamed.storageanalyzer.reactive.RerunState
import com.mohamed.storageanalyzer.sqlite.SQLiteDatabaseSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * A change-detection hook for [RoomStorageMonitor]. The default
 * implementation fingerprints `PRAGMA data_version`/`user_version`/page count
 * plus the db file length (see [RoomDatabaseAnalyzer.fingerprint]); Android
 * apps typically plug in Room's `InvalidationTracker` observer instead and
 * call [RoomStorageMonitor.refresh] on table changes.
 */
public fun interface RoomChangeProbe {
    /** Current content fingerprint, or null when unknown (unknown triggers a run). */
    public suspend fun fingerprint(): String?
}

/**
 * HOT monitor of a Room database (plus the COLD single-shot path).
 *
 * - **Hot**: [start] launches the rerun loop; changes in the database content
 *   (any connection commit — Room's own writes included) are detected through
 *   the [RoomChangeProbe] and produce a fresh [RoomAnalysis] in [results].
 *   Results are published only when the [RoomAnalysis.digest] changes, so
 *   consumers never re-render for identical data.
 * - **Cold**: [refreshNow] (suspend) or the underlying
 *   [RoomDatabaseAnalyzer.analyzeOnce] return single-shot results with no
 *   background state required.
 *
 * Lifecycle: the caller owns the [CoroutineScope]; `close()` stops this
 * monitor's jobs and its internal connections only. A failing turn keeps the
 * last good analysis visible (`RerunState.Failed.previous`) and the loop
 * retries on the next trigger.
 */
public class RoomStorageMonitor(
    spec: SQLiteDatabaseSpec,
    schemaBundle: RoomSchemaBundle? = null,
    scope: CoroutineScope,
    /** Cadence of the cheap fingerprint probe for file-backed databases. */
    pollInterval: Duration = Duration.ofSeconds(2),
    config: RoomAnalysisConfig = RoomAnalysisConfig(),
    options: AnalysisOptions? = null,
    /**
     * Optional replacement of the built-in SQLite fingerprint probe. Useful
     * for event-driven integrations (e.g. Room's InvalidationTracker) or
     * in-memory databases — set `RefreshPolicy`-neutral triggers instead of
     * polling when better signals exist.
     */
    changeProbe: RoomChangeProbe? = null,
    /** Dispatcher for the (usually blocking) analysis turn; default at production. */
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val analyzer = RoomDatabaseAnalyzer(spec, schemaBundle, config)

    private val activeProbe: RoomChangeProbe? = changeProbe
        ?: if (analyzer.fingerprintCapable) {
            RoomChangeProbe { withContext(dispatcher) { analyzer.fingerprint() } }
        } else {
            null // e.g. :memory: databases: no native fingerprint; drive with refresh()/refreshNow()
        }

    private val controller = com.mohamed.storageanalyzer.reactive.RerunController<RoomAnalysis>(
        userScope = scope,
        policy = when {
            activeProbe != null -> RefreshPolicy.OnChange(
                probe = { activeProbe.fingerprint() },
                pollInterval = pollInterval,
            )
            else -> RefreshPolicy.Manual
        },
        dispatcher = dispatcher,
        produce = {
            if (options != null) analyzer.analyzeOnce(options) else analyzer.analyzeOnce()
        },
        digestOf = { it.digest },
    )

    public val state: StateFlow<RerunState<RoomAnalysis>> get() = controller.state

    public val results: StateFlow<RoomAnalysis?> get() = controller.results

    /** The underlying one-shot analyzer (also usable directly for cold runs). */
    public val coldAnalyzer: RoomDatabaseAnalyzer get() = analyzer

    /** Starts the change-detection loop (idempotent). */
    public fun start(): Unit = controller.start()

    /** Fire-and-forget refresh; the InvalidationsTracker → monitor integration hook. */
    public fun refresh(trigger: String? = null): Unit = controller.refresh(trigger)

    /** Suspend single-shot refresh; returns the resulting state. */
    public suspend fun refreshNow(trigger: String? = null): RerunState<RoomAnalysis> =
        controller.refreshNow(trigger)

    override fun close() {
        controller.close()
        analyzer.close()
    }
}
