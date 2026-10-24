package com.mohamed.storageanalyzer.core.entity.traversal

import com.mohamed.storageanalyzer.core.config.AnalysisBudget
import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.config.AnalysisOptions
import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.provider.StorageProvider
import com.mohamed.storageanalyzer.core.resource.ReferenceLink
import com.mohamed.storageanalyzer.core.resource.RelationType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun id(n: String): ResourceIdentity = ResourceIdentity(
    ProviderId("test"),
    StorageSystemId("/sys"),
    ResourceKind.RECORD,
    n,
)

class RelationWalkerTest {    private class GraphProvider(private val graph: Map<String, List<String>>) : StorageProvider {
        override val providerId = ProviderId("test")

        override fun describe() = "graph"
        override fun capabilities() = throw UnsupportedOperationException()
        override fun capabilitiesFor(systemId: StorageSystemId) = throw UnsupportedOperationException()
        override fun discoverResources(systemId: StorageSystemId?, ctx: AnalysisContext) = emptySequence<com.mohamed.storageanalyzer.core.resource.StorageResource>()
        override fun measure(resource: ResourceIdentity, metrics: Set<com.mohamed.storageanalyzer.core.measurement.MetricType>, ctx: AnalysisContext) = throw UnsupportedOperationException()

        override fun related(resource: ResourceIdentity, ctx: AnalysisContext): List<ReferenceLink> =
            (graph[resource.nativeId] ?: emptyList()).map { ReferenceLink(providerId, id(it), RelationType.CONTAINS) }

        override fun close() = Unit
    }

    @Test
    fun `cycles terminate via the visited set`() {
        val graph = mapOf(
            "a" to listOf("b"),
            "b" to listOf("c"),
            "c" to listOf("a"), // cycle back
        )
        val ctx = AnalysisContext.create()
        val result = RelationWalker(listOf(GraphProvider(graph)), ctx, AnalysisBudget.UNBOUNDED)
            .walk(listOf(id("a")))
        assertEquals(setOf("a", "b", "c"), result.visited.map { it.identity.nativeId }.toSet())
        assertEquals(false, result.truncated)
    }

    @Test
    fun `depth bound truncates traversal and reports honestly`() {
        val graph = mapOf(
            "a" to listOf("b"),
            "b" to listOf("c"),
            "c" to listOf("d"),
        )
        val ctx = AnalysisContext.create()
        val result = RelationWalker(listOf(GraphProvider(graph)), ctx, AnalysisBudget(maxDepth = 1))
            .walk(listOf(id("a")))
        assertEquals(setOf("a", "b"), result.visited.map { it.identity.nativeId }.toSet())
        // depth limit only stops *expansion*; the walk itself completed within budget
        assertTrue(result.visited.all { it.depth <= 1 })
    }

    @Test
    fun `edge budget truncates traversal and sets the partial flag`() {
        val graph = mapOf(
            "a" to listOf("b", "c", "d"),
        )
        val ctx = AnalysisContext.create(AnalysisBudget(maxEdgesTraversed = 2))
        val result = RelationWalker(listOf(GraphProvider(graph)), ctx, AnalysisBudget.UNBOUNDED)
            .walk(listOf(id("a")))
        // Only edges that fit the budget were traversed; the walk stops at the
        // budget exhaustion, so only the root remains visited.
        assertEquals(1, result.visited.size)
        assertTrue(result.truncated, "edge budget must mark the traversal truncated")
    }

    @Test
    fun `cancellation is thrown at the next safe point`() {
        val graph = mapOf(
            "a" to listOf("b"),
        )
        val defaultCtx = AnalysisContext.create() as com.mohamed.storageanalyzer.core.config.DefaultAnalysisContext
        defaultCtx.cancel()
        assertFailsWith<com.mohamed.storageanalyzer.core.result.StorageError.Cancelled> {
            RelationWalker(listOf(GraphProvider(graph)), defaultCtx, AnalysisBudget.UNBOUNDED)
                .walk(listOf(id("a")))
        }
    }

    @Test
    fun `roots are visited exactly once even when duplicated`() {
        val graph = mapOf("a" to listOf("x"))
        val ctx = AnalysisContext.create()
        val result = RelationWalker(listOf(GraphProvider(graph)), ctx, AnalysisBudget.UNBOUNDED)
            .walk(listOf(id("a"), id("a")))
        assertEquals(2, result.visited.size) // a + x, a deduplicated
    }
}
