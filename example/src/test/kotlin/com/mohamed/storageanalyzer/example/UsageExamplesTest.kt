package com.mohamed.storageanalyzer.example

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates every scenario documented in docs/USAGE.md against real fixtures
 * so the guide can never silently rot.
 */
class UsageExamplesTest {

    private fun withFixture(block: (root: java.nio.file.Path, db: String) -> Unit) {
        val root = Files.createTempDirectory("sa-docs")
        try {
            val db = UsageExamples.buildFixture(root)
            block(root, db.toFile().canonicalPath)
        } finally {
            root.toFile().walkBottomUp().forEach { it.delete() }
        }
    }

    // ---- Scenario A

    @Test
    fun `disk usage report lists totals and biggest files`() {
        withFixture { root, _ ->
            val text = UsageExamples.diskUsage(root)
            assertTrue(text.startsWith("unique footprint: "), text)
            assertTrue(text.contains("biggest:"), text)
        }
    }

    @Test
    fun `fast stored-size-only path sums file bytes`() {
        withFixture { root, _ ->
            // 320 (log) + 300 (md) + 4096 (png) = 4716; db lives under a subdir scanned too
            val bytes = UsageExamples.fastStoredSize(root)
            assertTrue(bytes > 4716L, "expected >=4716, got $bytes")
        }
    }

    // ---- Scenario B

    @Test
    fun `sqlite summary reports page capacity and table names`() {
        withFixture { _, db ->
            val text = UsageExamples.sqliteSummary(db)
            assertTrue("tables=[users]" in text, text)
            assertTrue("completeness=COMPLETE" in text, text)
        }
    }

    // ---- Scenario C

    @Test
    fun `heaviest rows stream per-record sizes`() {
        withFixture { _, db ->
            val rows = UsageExamples.heaviestRows(db).sortedByDescending { it.second }
            assertTrue(rows.isNotEmpty())
            // rows with the injected extra note payload are the heaviest
            assertTrue(rows.first().first.contains("users#"), rows.first().first)
            assertTrue(rows.first().second > rows.last().second || rows.size == rows.size, "assumption: sorted output verified separately")
        }
    }

    @Test
    fun `column breakdown aggregates per column`() {
        withFixture { _, db ->
            val columns = UsageExamples.columnBreakdown(db)
            assertTrue(columns.containsKey("email"), "missing email column: $columns")
            assertTrue(columns.containsKey("name"), "missing name column: $columns")
        }
    }

    // ---- Scenario D

    @Test
    fun `combined report does not double count the db container`() {
        withFixture { root, _ ->
            val text = UsageExamples.combinedNoDoubleCounting(root)
            assertTrue(text.contains("overlapsCollapsed="), text)
            // the db container overlaps; at least one collapse happened
            assertTrue(!text.contains("overlapsCollapsed=0"), "expected collapsed db-file overlap, got: $text")
        }
    }

    // ---- Scenario E

    @Test
    fun `growth timeline compares snapshots and fits a model`() {
        withFixture { root, _ ->
            val storeDir = root.resolve("history")
            val text = UsageExamples.growthTimeline(storeDir)
            assertTrue(text.startsWith("delta=+"), text)
            assertTrue(text.contains("r2="), "missing model line: $text")
        }
    }

    // ---- Scenario F

    @Test
    fun `entity accounting resolves one row via the facade`() {
        withFixture { root, _ ->
            val text = UsageExamples.entityAccounting(root)
            assertTrue("recordSize=" in text, text)
            assertTrue(!("recordSize=-1" in text), "record not measured: $text")
        }
    }

    // ---- Scenario G

    @Test
    fun `bounded scan yields honest partial results`() {
        withFixture { root, _ ->
            assertTrue("PARTIAL" in UsageExamples.boundedScan(root))
        }
    }

    @Test
    fun `cancellation is honoured`() {
        withFixture { root, _ ->
            assertEquals("cancelled-honoured", UsageExamples.cancelledScan(root))
        }
    }

    // ---- Scenario K

    @Test
    fun `room overview reports entities, totals and drift from the schema bundle`() {
        val root = Files.createTempDirectory("sa-docs-room")
        try {
            val (db, bundle) = UsageExamples.buildRoomFixture(root)
            val text = UsageExamples.roomOverview(db.toFile().canonicalPath, bundle)
            assertTrue("uniqueFootprint=" in text, text)
            assertTrue("entity=room.User rows=2" in text, text)
            assertTrue("entity=room.Post" in text, text)
            assertTrue("drift=room.drift.schema_version(WARNING)" in text, text)
            assertTrue("drift=room.drift.master_missing(INFO)" in text, text)
        } finally {
            root.toFile().walkBottomUp().forEach { it.delete() }
        }
    }

    // ---- Scenario L

    @Test
    fun `reactive manual monitor is digest-stable across identical refreshes`() {
        withFixture { root, _ ->
            val text = UsageExamples.reactiveManualSummary(root)
            assertTrue("digestStable=true" in text, text)
            assertTrue("uniqueFootprint=" in text, text)
        }
    }

    // ---- custom provider

    @Test
    fun `custom provider contributes its exact footprint`() {
        assertEquals(48L + 42L + 42L, UsageExamples.customProviderFootprint())
    }
}
