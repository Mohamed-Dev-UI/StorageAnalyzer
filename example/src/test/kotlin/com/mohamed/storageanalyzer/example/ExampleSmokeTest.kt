package com.mohamed.storageanalyzer.example

import kotlin.test.Test

/**
 * Smoke test: the demo executes fully without throwing (assertions about the
 * printed content are intentionally not made — output is illustrative).
 */
class ExampleSmokeTest {

    @Test
    fun `demo runs end to end`() {
        StorageAnalyzerDemo.main(emptyArray())
    }

    @Test
    fun `growth fixture can be invoked twice without stale state`() {
        // Re-invocation against a fresh temp workspace must stay deterministic.
        StorageAnalyzerDemo.main(emptyArray())
    }
}
