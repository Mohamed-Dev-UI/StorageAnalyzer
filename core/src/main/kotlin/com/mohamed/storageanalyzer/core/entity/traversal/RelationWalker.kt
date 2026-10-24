package com.mohamed.storageanalyzer.core.entity.traversal

import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import java.util.ArrayDeque

/**
 * Bounded, cycle-safe, deterministic traversal of the storage resource graph.
 *
 * Guarantees:
 * - **cycle detection**: visited set keyed by [ResourceIdentity.stableKey];
 * - **bounded depth**: [AnalysisBudget.maxDepth] hops from the roots;
 * - **bounded work**: max-edges budget (per-run, shared across the traversal);
 * - **cancellation**: context checked at every safe point;
 * - **determinism**: neighbors resolved in provider order and stable edge order.
 *
 * The traversal only stores identities + depth in the frontier; resources are
 * never materialized here, keeping memory bounded even for large graphs.
 */
class RelationWalker(
    private val providers: Collection<StorageProvider>,
    private val ctx: com.mohamed.storageanalyzer.core.config.BudgetedAnalysisContext,
    private val budget: AnalysisBudget,
) {

    /** One reached node with its arrival edge (null edge for roots). */
    data class Visited(
        val identity: ResourceIdentity,
        /** Identity of the resource this node was reached from; null for roots. */
        val parent: ResourceIdentity?,
        /** Arrival relation; null for roots. */
        val relation: String?,
        /** Arrival edge label; null for roots/none. */
        val edgeLabel: String?,
        val depth: Int,
    )

    data class TraversalResult(
        val visited: List<Visited>,
        /** True when a budget/cancellation bound stopped the walk early. */
        val truncated: Boolean,
    ) {
        val identities: List<ResourceIdentity> get() = visited.map { it.identity }
    }

    fun walk(
        roots: List<ResourceIdentity>,
        extraEdges: (ResourceIdentity) -> List<ReferenceLink> = { emptyList() },
    ): TraversalResult {
        val visitedByKey = LinkedHashMap<String, Visited>()
        val maxDepth = budget.maxDepth ?: 8
        val orderedProviders = providers.sortedBy { it.providerId.value }
        val frontier = ArrayDeque<Queued>()
        for (r in roots.distinctBy { it.stableKey }) {
            frontier.add(Queued(r, 0, null, null, null))
        }
        var truncated = false
        var stop = false

        while (frontier.isNotEmpty()) {
            ctx.ensureActive()
            if (stop) break
            if (!ctx.timeRemaining()) {
                truncated = true
                break
            }
            val node = frontier.removeFirst()
            val key = node.identity.stableKey
            if (key in visitedByKey) continue
            visitedByKey[key] = Visited(
                identity = node.identity,
                parent = node.parent,
                relation = node.relation,
                edgeLabel = node.label,
                depth = node.depth,
            )
            if (node.depth >= maxDepth) continue

            for (p in orderedProviders) {
                if (stop) break
                val providerEdges = try {
                    p.related(node.identity, ctx)
                } catch (e: Exception) {
                    ctx.diagnostic(digraphFailure(node.identity, p, e))
                    continue
                }
                val edges = (extraEdges(node.identity) + providerEdges)
                    .sortedWith(compareBy({ it.toIdentity.stableKey }, { it.relation.name }))
                for (edge in edges) {
                    if (!ctx.consumeEdge()) {
                        // Edge budget exhausted: stop the walk; queued nodes
                        // that were not reached through a budgeted edge are
                        // not reported as visited (honest partial results).
                        truncated = true
                        stop = true
                        break
                    }
                    val targetKey = edge.toIdentity.stableKey
                    if (targetKey in visitedByKey) continue
                    frontier.add(
                        Queued(
                            identity = edge.toIdentity,
                            depth = node.depth + 1,
                            parent = node.identity,
                            relation = edge.relation.name,
                            label = edge.label,
                        )
                    )
                }
                ctx.ensureActive()
            }
        }
        return TraversalResult(visitedByKey.values.toList(), truncated)
    }

    private fun digraphFailure(identity: ResourceIdentity, provider: StorageProvider, e: Exception): StorageDiagnostic =
        StorageDiagnostic(
            code = "traverse.provider_failed",
            severity = DiagnosticSeverity.WARNING,
            message = "provider '${provider.providerId}' failed while resolving relations: " +
                (e.message ?: e.javaClass.simpleName),
            identity = identity,
            providerId = provider.providerId,
        )

    private data class Queued(
        val identity: ResourceIdentity,
        val depth: Int,
        val parent: ResourceIdentity?,
        val relation: String?,
        val label: String?,
    )
}
