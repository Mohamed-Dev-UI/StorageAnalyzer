package com.mohamed.storageanalyzer.core.analytics

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalyzerConfig
import com.mohamed.storageanalyzer.core.capability.Capabilities
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.resource.StorageResource

/**
 * Internal inventory entry: one unique resource, its measuring provider and
 * the bookkeeping for cross-provider overlaps.
 */
internal data class InventoryEntry(
    val resource: StorageResource,
    val measuredByProvider: StorageProvider,
    /** Keys of alternative identities that would also discover this resource. */
    val alsoDiscoverableKeys: Set<String>,
)
/**
 * Deduplicated, deterministic inventory of discoverable resources.
 *
 * Dedup rules:
 * 1. A resource identity ([stableKey]) seen an earlier time is skipped; the
 *    first provider in deterministic order measures it.
 * 2. When a provider declares [alsoDiscoverableBy] identities that appeared
 *    under another provider, that entry is recorded but excluded from unique
 *    totals; the earlier discovery wins. This is exactly what prevents a
 *    `.sqlite` file from being counted twice (as file AND as database).
 * 3. Duplicates never fail the run — they are reported as diagnostics.
 */
internal class InventoryComposer(
    private val providers: List<StorageProvider>,
    private val ctx: com.mohamed.storageanalyzer.core.config.BudgetedAnalysisContext,
    private val excluded: (com.mohamed.storageanalyzer.core.identity.ResourceIdentity) -> Boolean = { false },
) {

    private val byKey = LinkedHashMap<String, InventoryEntry>()
    private val overlaps = LinkedHashMap<String, OverlapRecord>()

    /** Identities already dropped by the run-level exclusion predicate. */
    private val skippedKeys = HashSet<String>()

    /** How many discovered resources the exclusion predicate dropped. */
    var excludedCount: Int = 0
        private set

    data class OverlapRecord(
        val duplicateIdentity: com.mohamed.storageanalyzer.core.identity.ResourceIdentity,
        val excludedProviderId: String,
        val canonicalKey: String,
    )

    /** Composes the inventory; streaming per provider to bound memory. */
    fun compose(): List<InventoryEntry> {
        for (provider in providers) {
            val seq = provider.discoverResources(null, ctx)
            for (resource in seq) {
                ctx.ensureActive()
                if (!ctx.timeRemaining()) break
                if (excluded(resource.identity)) {
                    excludedCount += 1
                    skippedKeys += resource.identity.stableKey
                    // Aliased identities of the same physical resource drop too,
                    // so dedup semantics cannot resurrect an excluded resource.
                    for (alias in resource.alsoDiscoverableBy) skippedKeys += alias.stableKey
                    continue
                }
                val key = resource.identity.stableKey
                if (byKey.containsKey(key)) {
                    overlaps[key] = OverlapRecord(resource.identity, provider.providerId.value, key)
                    continue
                }
                if (skippedKeys.contains(key)) continue
                val overlapWithEarlier = resource.alsoDiscoverableBy.firstOrNull {
                    byKey.containsKey(it.stableKey) || skippedKeys.contains(it.stableKey)
                }
                if (overlapWithEarlier != null) {
                    overlaps[resource.identity.stableKey] = OverlapRecord(
                        resource.identity,
                        provider.providerId.value,
                        overlapWithEarlier.stableKey,
                    )
                    continue
                }
                byKey[key] = InventoryEntry(resource, provider, resource.alsoDiscoverableBy.map { it.stableKey }.toSet())
            }
        }
        return byKey.values.toList()
    }

    fun overlapsCount(): Int = overlaps.size

    fun overlapRecords(): List<OverlapRecord> = overlaps.values.toList()
}
