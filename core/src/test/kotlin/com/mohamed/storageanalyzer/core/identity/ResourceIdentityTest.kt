package com.mohamed.storageanalyzer.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceIdentityTest {

    private fun id(
        provider: String = "fs",
        system: String = "/sys",
        kind: ResourceKind = ResourceKind.FILE,
        native: String = "a.txt",
        namespace: String? = null,
        generation: String? = null,
    ) = ResourceIdentity(
        ProviderId(provider),
        StorageSystemId(system),
        kind,
        native,
        namespace,
        generation,
    )

    @Test
    fun `stableKey escapes separators so identities stay injective`() {
        val a = id(native = "x/y")
        val b = id(native = "x", namespace = "y")
        // Without escaping, naive joins of both would collide; with encoding they do not.
        assertNotEquals(a.stableKey, b.stableKey)
    }

    @Test
    fun `nasty identities never collide`() {
        val nasty1 = id(native = "a%2Fb", namespace = "c")
        val nasty2 = id(native = "a", namespace = "2Fb%2Fc")
        assertNotEquals(nasty1.stableKey, nasty2.stableKey)
        val unicode = id(native = "ф%2F", generation = "🙂")
        assertTrue(unicode.stableKey.contains('%'))
        assertNotEquals(unicode.stableKey, id(native = "ф", generation = "🙂2F").stableKey)
    }

    @Test
    fun `same components produce identical stableKeys`() {
        assertEquals(
            id(provider = "sqlite", native = "t:orders:42").stableKey,
            id(provider = "sqlite", native = "t:orders:42").stableKey,
        )
    }

    @Test
    fun `immutable fields validated`() {
        assertFailsWith<IllegalArgumentException> { id(native = "") }
        assertFailsWith<IllegalArgumentException> { id(provider = "bad:provider") }
        assertFailsWith<IllegalArgumentException> { id(native = "x\u0000") }
    }

    @Test
    fun `stability declaration is part of identity semantics not of the key`() {
        val stable = id(native = "f.txt", generation = null)
        val ephemeral = stable.copy(stability = IdentityStability.EPHEMERAL)
        // changing stability does not change the key (same resource, different declaration)
        assertEquals(stable.stableKey, ephemeral.stableKey)
    }
}
