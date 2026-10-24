package com.mohamed.storageanalyzer.testing

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisProgress
import com.mohamed.storageanalyzer.core.config.BudgetedAnalysisContext
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import java.time.Duration
import java.time.Instant

/**
 * Fully-scriptable [AnalysisContext] / [BudgetedAnalysisContext] for tests:
 * flip [cancelled] to exercise cancellation paths, set the counters to limit
 * budgets, and assert on the collected events afterwards.
 */
class RecordingContext(
    var resourcesBudget: Long = Long.MAX_VALUE,
    var edgesBudget: Long = Long.MAX_VALUE,
    var timedOut: Boolean = false,
) : BudgetedAnalysisContext {

    private val cancelledFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    var cancelled: Boolean
        get() = cancelledFlag.get()
        set(value) = cancelledFlag.set(value)

    val diagnostics = ArrayList<StorageDiagnostic>()
    val progressEvents = ArrayList<AnalysisProgress>()
    var resourcesConsumed: Long = 0
        private set
    var edgesConsumed: Long = 0
        private set

    override fun isCancelled(): Boolean = cancelledFlag.get()

    override fun elapsed(): Duration = Duration.ofSeconds(1)

    override fun deadline(): Instant? = null

    override fun report(event: AnalysisProgress) {
        progressEvents.add(event)
    }

    override fun diagnostic(diagnostic: StorageDiagnostic) {
        diagnostics.add(diagnostic)
    }

    override fun timeRemaining(): Boolean = !timedOut && !cancelledFlag.get()

    override fun consumeResource(): Boolean {
        if (resourcesConsumed >= resourcesBudget) return false
        resourcesConsumed++
        return true
    }

    override fun consumeEdge(): Boolean {
        if (edgesConsumed >= edgesBudget) return false
        edgesConsumed++
        return true
    }

    fun reset() {
        cancelledFlag.set(false)
        diagnostics.clear()
        progressEvents.clear()
        resourcesConsumed = 0
        edgesConsumed = 0
    }

    fun diagnosticsOfCode(code: String): List<StorageDiagnostic> = diagnostics.filter { it.code == code }
}

/** Convenience builder for test identities. */
fun testIdentity(
    provider: String,
    system: String,
    kind: com.mohamed.storageanalyzer.core.identity.ResourceKind =
        com.mohamed.storageanalyzer.core.identity.ResourceKind.FILE,
    nativeId: String = "n",
    namespace: String? = null,
): ResourceIdentity = ResourceIdentity(
    providerId = com.mohamed.storageanalyzer.core.identity.ProviderId(provider),
    systemId = com.mohamed.storageanalyzer.core.identity.StorageSystemId(system),
    kind = kind,
    nativeId = nativeId,
    namespace = namespace,
)

/** A temp directory that deletes itself on close (recursively). */
class TempDir(prefix: String = "storage-analyzer-test") : java.io.Closeable {
    val root: java.nio.file.Path = java.nio.file.Files.createTempDirectory(prefix)

    fun file(name: String, content: ByteArray = ByteArray(0)): java.nio.file.Path =
        java.nio.file.Files.write(root.resolve(name), content)

    override fun close() {
        root.toFile().walkBottomUp().forEach { it.delete() }
    }
}
