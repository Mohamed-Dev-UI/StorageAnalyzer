package com.mohamed.storageanalyzer.core.entity

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.config.AnalyzerConfig
import com.mohamed.storageanalyzer.core.entity.spi.EntityResourceResolver
import com.mohamed.storageanalyzer.core.entity.traversal.RelationWalker
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.policy.AccountingPolicy
import com.mohamed.storageanalyzer.core.policy.Allocation
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.EntityReport
import com.mohamed.storageanalyzer.core.result.RecordBreakdown
import com.mohamed.storageanalyzer.core.result.SharedResourceAllocation
import com.mohamed.storageanalyzer.core.result.StorageDiagnostic
import com.mohamed.storageanalyzer.core.result.StorageError
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.time.Instant

/**
 * Produces entity storage views using provider measurements + descriptor
 * relations + the configured [policy].
 *
 * Implemented accounting views (see docs/ACCOUNTING.md for exact semantics):
 * - direct: metrics of the entity's own backing resources;
 * - inclusive: sum over entity instances of their uniquely-reached resources
 *   (shared resources are counted once per instance that reaches them);
 * - uniqueReachable: all reached resources counted once globally;
 * - exclusive: resources with a single claimant, plus (under
 *   [AccountingPolicies.CANONICAL_OWNER]) canonical claims of shared nodes;
 * - sharedAllocated / sharedNotAttributed: policy attribution results.
 *
 * Determinism: owner claims are sorted by id; walking and measurement orders
 * are deterministic; shared-node allocations use the documented policies.
 */
internal class EntityAnalyzer(
    private val providers: List<StorageProvider>,
    private val config: AnalyzerConfig,
    private val options: AnalysisOptions,
    private val policy: AccountingPolicy,
    private val ctx: com.mohamed.storageanalyzer.core.config.BudgetedAnalysisContext,
) {

    private class Node {
        var measurement: StorageMeasurement? = null
        val reachedByRootKeys: MutableSet<String> = LinkedHashSet()
    }

    private data class InstanceIdentity(val instanceKey: String, val identity: ResourceIdentity)

    private fun descriptorEdges(descriptor: EntityDescriptor): (ResourceIdentity) -> List<ReferenceLink> = { id ->
        if (!options.includeRelations || !descriptor.relations.let { it.isNotEmpty() }) emptyList()
        else descriptor.relations.flatMap { rel ->
            if (!rel.traversable) emptyList()
            else {
                runCatching { rel.resolver.resolve(id) }.getOrElse { emptyList() }
            }
        }
    }

    // ------------------------------------------------------------------ single

    fun analyze(instanceKey: EntityInstanceKey, descriptor: EntityDescriptor): EntityReport {
        val provider = requireProvider(descriptor.providerId)
        val root = descriptor.identityOf(instanceKey)
        val walker = RelationWalker(providers, ctx, options.budget)
        val visited = walker.walk(listOf(root), descriptorEdges(descriptor))

        val nodes = LinkedHashMap<String, Node>()
        var truncated = false
        measureVisited(visited, nodes).also { truncated = it }
        val rootMeasurement = nodes[root.stableKey]?.measurement
        val allMeasurements = nodes.values.mapNotNull { it.measurement }
        val direct = aggregateByMetric(listOfNotNull(rootMeasurement))
        val reachable = aggregateByMetric(allMeasurements)

        // Provider-declared ENTITY owners of reached nodes; single-instance runs
        // attribute resources declared shared among entities by the provider.
        val entityShared = allMeasurements.filter { declaredEntityOwners(it) >= 2 }
        val sharedAllocations = mutableListOf<SharedResourceAllocation>()
        val sharedAllocated = mutableMapOf<MetricType, ByteCount>()
        var sharedNotAttributed = ByteCount.ZERO
        val selfRef = OwnerRef(instanceKey.value, OwnerRef.OwnerKind.ENTITY, entityTypeId = descriptor.entityTypeId)
        for (m in entityShared) {
            val bytes = m.valueOf(config.footprintMetric) ?: continue
            alloc(m, bytes, m.owners).let { allocation ->
                sharedAllocations += SharedResourceAllocation(m.identity, bytes, m.owners.size, allocation.shares, policy.policyId)
                val own = allocation.shares[selfRef]
                if (own != null && own > ByteCount.ZERO) {
                    sharedAllocated[config.footprintMetric] = (sharedAllocated[config.footprintMetric] ?: ByteCount.ZERO) + own
                }
                if (allocation.shares.isEmpty()) sharedNotAttributed = sharedNotAttributed + bytes
            }
        }
        val breakdowns = collectFieldBreakdowns(descriptor, provider, listOf(root))

        return EntityReport(
            entityTypeId = descriptor.entityTypeId,
            providerId = provider.providerId,
            instances = listOf(instanceKey),
            instanceCount = 1,
            direct = direct,
            exclusive = reachable, // single-claimant by construction (see docs)
            inclusive = reachable,
            uniqueReachable = reachable,
            sharedAllocated = sharedAllocated,
            sharedNotAttributed = if (sharedNotAttributed > ByteCount.ZERO) mapOf(config.footprintMetric to sharedNotAttributed) else emptyMap(),
            attributedPhysical = sharedAllocated,
            uniqueResourceCount = nodes.size.toLong(),
            accountingPolicy = policy,
            perFieldBreakdowns = breakdowns,
            sharedAllocations = sharedAllocations,
            diagnostics = emptyList(),
            completeness = if (truncated) Completeness.PARTIAL else Completeness.COMPLETE,
            measuredAt = Instant.now(),
        )
    }

    // --------------------------------------------------------------- aggregate

    fun analyzeAll(descriptor: EntityDescriptor): EntityReport {
        val provider = requireProvider(descriptor.providerId)
        val resolver = provider.entityResolver()
        if (resolver == null) {
            throw StorageError.UnsupportedCapability(
                capabilityId = "entity.resources",
                reason = "provider '${descriptor.providerId}' cannot enumerate instances of '${descriptor.entityTypeId}'",
            )
        }
        val roots = sequence {
            for (res in resolver.resourcesFor(descriptor, ctx)) {
                ctx.ensureActive()
                yield(res.identity)
            }
        }.distinctBy { it.stableKey }.toList()
        if (roots.isEmpty()) return emptyReport(descriptor, provider.providerId)

        var truncated = false
        val nodes = LinkedHashMap<String, Node>()
        val walker = RelationWalker(providers, ctx, options.budget)
        for (root in roots) {
            if (!ctx.consumeResource()) { truncated = true; break }
            val visited = walker.walk(listOf(root), descriptorEdges(descriptor))
            truncated = truncated || visited.truncated
            for (vi in visited.visited) {
                val node = nodes.getOrPut(vi.identity.stableKey) { Node() }
                node.reachedByRootKeys += root.stableKey
                measureNodeOnce(vi.identity, node)
            }
        }

        val inclusive = mutableMapOf<MetricType, ByteCount>()
        for (node in nodes.values) {
            val m = node.measurement ?: continue
            repeat(node.reachedByRootKeys.size) {
                addMeasurementTo(inclusive, m)
            }
        }
        val uniqueReachable = mutableMapOf<MetricType, ByteCount>()
        for (node in nodes.values) node.measurement?.let { addMeasurementTo(uniqueReachable, it) }

        val rootKeys = roots.map { it.stableKey }.toSet()
        val direct = aggregateByMetric(nodes.filterKeys { it in rootKeys }.values.mapNotNull { it.measurement })

        // Shared amongst reached instances (no provider-declared ENTITY owners):
        val sharedNodes = nodes.values
            .filter { (it.measurement?.let { m -> declaredEntityOwners(m) >= 2 } == false) && it.reachedByRootKeys.size > 1 }
        val sharedAllocations = mutableListOf<SharedResourceAllocation>()
        val sharedAllocated = mutableMapOf<MetricType, ByteCount>()
        var sharedNotAttributed = ByteCount.ZERO
        for (node in sharedNodes) {
            val m = node.measurement ?: continue
            val bytes = m.valueOf(config.footprintMetric) ?: continue
            val owners = node.reachedByRootKeys
                .map { entitiesRootKeyToOwnerRef(it, roots) }
                .filterNotNull()
                .sortedBy { it.id }
            val allocation = policy.allocate(m.identity, bytes, owners)
            sharedAllocations += SharedResourceAllocation(m.identity, bytes, owners.size, allocation.shares, policy.policyId)
            for (v in allocation.shares.values) {
                sharedAllocated[config.footprintMetric] = (sharedAllocated[config.footprintMetric] ?: ByteCount.ZERO) + v
            }
            if (allocation.shares.isEmpty()) {
                sharedNotAttributed = sharedNotAttributed + bytes
            }
        }

        // Exclusive:
        val exclusiveEntries = mutableListOf<StorageMeasurement>()
        for (node in nodes.values) {
            val m = node.measurement ?: continue
            if (node.reachedByRootKeys.size == 1) {
                if (declaredEntityOwners(m) < 2) exclusiveEntries.add(m)
            }
        }
        // shared aggregation is attributed via sharedAllocated; exclusive does not double count them.
        val exclusive = aggregateByMetric(exclusiveEntries).toMutableMap()
        val attributedPhysical = mutableMapOf<MetricType, ByteCount>()
        for ((metric, bytes) in sharedAllocated) {
            attributedPhysical[metric] = (attributedPhysical[metric] ?: ByteCount.ZERO) + bytes
        }

        val breakdowns = collectFieldBreakdowns(descriptor, provider, roots)

        return EntityReport(
            entityTypeId = descriptor.entityTypeId,
            providerId = provider.providerId,
            instances = null,
            instanceCount = roots.size.toLong(),
            direct = direct,
            exclusive = exclusive,
            inclusive = inclusive,
            uniqueReachable = uniqueReachable,
            sharedAllocated = sharedAllocated,
            sharedNotAttributed = if (sharedNotAttributed > ByteCount.ZERO) mapOf(config.footprintMetric to sharedNotAttributed) else emptyMap(),
            attributedPhysical = attributedPhysical,
            uniqueResourceCount = nodes.size.toLong(),
            accountingPolicy = policy,
            perFieldBreakdowns = breakdowns,
            sharedAllocations = sharedAllocations,
            diagnostics = emptyList(),
            completeness = if (truncated) Completeness.PARTIAL else Completeness.COMPLETE,
            measuredAt = Instant.now(),
        )
    }

    private fun entitiesRootKeyToOwnerRef(rootKey: String, roots: List<ResourceIdentity>): OwnerRef? {
        val root = roots.firstOrNull { it.stableKey == rootKey } ?: return null
        return OwnerRef(root.nativeId, OwnerRef.OwnerKind.ENTITY, root.namespace)
    }

    private fun declaredEntityOwners(m: StorageMeasurement): Int =
        m.owners.count { it.kind == OwnerRef.OwnerKind.ENTITY }

    // ---------------------------------------------------------------- helpers

    private fun alloc(m: StorageMeasurement, bytes: ByteCount, owners: List<OwnerRef>) =
        policy.allocate(m.identity, bytes, owners)

    private fun measureVisited(
        visited: RelationWalker.TraversalResult,
        nodes: LinkedHashMap<String, Node>,
    ): Boolean {
        for (vi in visited.visited) {
            nodes.getOrPut(vi.identity.stableKey) { Node() }
            measureNodeOnce(vi.identity, nodes.getValue(vi.identity.stableKey))
        }
        return visited.truncated
    }

    private fun measureNodeOnce(identity: ResourceIdentity, node: Node): Boolean {
        if (node.measurement != null) return false
        val provider = providers.firstOrNull { it.providerId == identity.providerId } ?: return false
        node.measurement = try {
            provider.measure(identity, options.metrics, ctx)
        } catch (e: Exception) {
            ctx.diagnostic(warning("entity.provider_failed", provider, identity, e))
            return false
        }
        return true
    }

    private fun collectFieldBreakdowns(
        descriptor: EntityDescriptor,
        provider: StorageProvider,
        roots: List<ResourceIdentity>,
    ): List<RecordBreakdown> {
        if (!options.includeRelations || roots.isEmpty()) return emptyList()
        val engine = provider.recordAnalytics() ?: return emptyList()
        val out = mutableListOf<RecordBreakdown>()
        for (root in roots) {
            if (!ctx.timeRemaining() || !ctx.consumeResource()) break
            val bd = runCatching { engine.fieldBreakdown(root, ctx) }.getOrNull() ?: continue
            out += bd
        }
        return out
    }

    private fun aggregateByMetric(measurements: List<StorageMeasurement>): Map<MetricType, ByteCount> {
        val out = mutableMapOf<MetricType, ByteCount>()
        for (m in measurements) addMeasurementTo(out, m)
        return out
    }

    private fun addMeasurementTo(total: MutableMap<MetricType, ByteCount>, m: StorageMeasurement) {
        for ((metric, single) in m.measurements) {
            if (single.state != MeasurementState.MEASURED) continue
            val v = single.value ?: continue
            total.merge(metric, v, ByteCount::plus)
        }
    }

    private fun requireProvider(providerId: ProviderId): StorageProvider =
        providers.firstOrNull { it.providerId == providerId }
            ?: throw StorageError.ProviderUnavailable(providerId.value, "provider not registered")

    private fun emptyReport(descriptor: EntityDescriptor, providerId: ProviderId): EntityReport = EntityReport(
        entityTypeId = descriptor.entityTypeId,
        providerId = providerId,
        instances = emptyList(),
        instanceCount = 0,
        direct = emptyMap(),
        exclusive = emptyMap(),
        inclusive = emptyMap(),
        uniqueReachable = emptyMap(),
        sharedAllocated = emptyMap(),
        sharedNotAttributed = emptyMap(),
        attributedPhysical = emptyMap(),
        uniqueResourceCount = 0,
        accountingPolicy = policy,
        completeness = Completeness.COMPLETE,
        measuredAt = Instant.now(),
    )

    private fun warning(code: String, provider: StorageProvider, identity: ResourceIdentity, e: Exception): StorageDiagnostic =
        StorageDiagnostic(
            code = code,
            severity = DiagnosticSeverity.WARNING,
            message = "provider '${provider.providerId.value}': " + (
                e.message ?: e.javaClass.simpleName
                ),
            identity = identity,
            providerId = provider.providerId,
        )
}
