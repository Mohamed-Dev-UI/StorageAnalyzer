package com.mohamed.storageanalyzer.core.policy

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.identity.StorageSystemId
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingPoliciesTest {

    private fun sharedIdentity() = ResourceIdentity(
        ProviderId("fs"),
        StorageSystemId("/vol"),
        ResourceKind.FILE,
        "shared.bin",
    )

    private val a = OwnerRef("A-1", OwnerRef.OwnerKind.ENTITY, entityTypeId = "T")
    private val b = OwnerRef("B-2", OwnerRef.OwnerKind.ENTITY, entityTypeId = "T")
    private val c = OwnerRef("C-3", OwnerRef.OwnerKind.ENTITY, entityTypeId = "T")

    private fun totalOf(allocation: Allocation) = allocation.shares.values.fold(ByteCount.ZERO, ByteCount::plus) + allocation.unattributed

    @Test
    fun `count once globally attributes nothing (shared bytes stay in the shared bucket)`() {
        val alloc = AccountingPolicies.COUNT_ONCE_GLOBALLY.allocate(sharedIdentity(), ByteCount(100), listOf(a, b))
        assertTrue(alloc.shares.isEmpty())
        assertEquals(ByteCount(0), alloc.unattributed) // reported via sharedOnly bucket, not unattributed
    }

    @Test
    fun `canonical owner attaches all bytes to the smallest owner id deterministically`() {
        val alloc = AccountingPolicies.CANONICAL_OWNER.allocate(sharedIdentity(), ByteCount(101), listOf(c, a, b))
        assertEquals(ByteCount(101), alloc.shares[a])
        assertEquals(ByteCount(0), alloc.shares[b])
        assertEquals(ByteCount(0), alloc.shares[c])
        assertEquals(ByteCount(101), totalOf(alloc))
        // determinism across repeated calls
        assertEquals(alloc, AccountingPolicies.CANONICAL_OWNER.allocate(sharedIdentity(), ByteCount(101), listOf(a, b, c)))
    }

    @Test
    fun `equal split distributes every byte deterministically`() {
        // 100 bytes to 3 owners: 33+33+34 with remainder to the id-ordered first owner.
        val alloc = AccountingPolicies.EQUAL_SPLIT.allocate(sharedIdentity(), ByteCount(100), listOf(c, a, b))
        assertEquals(ByteCount(34), alloc.shares[a])
        assertEquals(ByteCount(33), alloc.shares[b])
        assertEquals(ByteCount(33), alloc.shares[c])
        assertEquals(ByteCount(100), totalOf(alloc))

        val exact = AccountingPolicies.EQUAL_SPLIT.allocate(sharedIdentity(), ByteCount(99), listOf(a, b, c))
        assertEquals(ByteCount(33), exact.shares.getValue(a))
        assertEquals(ByteCount(33), exact.shares.getValue(b))
        assertEquals(ByteCount(33), exact.shares.getValue(c))
    }

    @Test
    fun `proportional allocation conserves every byte and falls back to equal split without weights`() {
        val weightsProvider = { _: ResourceIdentity, owners: List<OwnerRef> ->
            owners.associateWith { o -> if (o == a) 2L else 1L }
        }
        val policy = AccountingPolicies.Proportional(weightsProvider)
        val alloc = policy.allocate(sharedIdentity(), ByteCount(120), listOf(a, b, c))
        assertEquals(ByteCount(60), alloc.shares.getValue(a))
        assertEquals(ByteCount(30), alloc.shares.getValue(b))
        assertEquals(ByteCount(30), alloc.shares.getValue(c))
        assertEquals(ByteCount(120), totalOf(alloc))

        val fallback = AccountingPolicies.Proportional { _, _ -> null }
        val distributed = fallback.allocate(sharedIdentity(), ByteCount(10), listOf(a, b))
        assertEquals(ByteCount(5), distributed.shares.getValue(a))
        assertEquals(ByteCount(5), distributed.shares.getValue(b))
    }

    @Test
    fun `odd division remainder bytes are round-robin in owner id order`() {
        val alloc = AccountingPolicies.Proportional({ _, owners -> owners.associateWith { 1L } })
            .allocate(sharedIdentity(), ByteCount(8), listOf(b, a, c))
        assertEquals(ByteCount(3), alloc.shares.getValue(a))
        assertEquals(ByteCount(3), alloc.shares.getValue(b))
        assertEquals(ByteCount(2), alloc.shares.getValue(c))
        assertEquals(ByteCount(8), totalOf(alloc))
    }

    @Test
    fun `small remainders and time test`() {
        // remainder to last: 10 → (3,3,3)+1 → last sorted owner gets +1 (a)
        val alloc = AccountingPolicies.EQUAL_SPLIT.allocate(sharedIdentity(), ByteCount(10), listOf(b, c, a))
        assertEquals(3L, alloc.shares.getValue(b).value)
        assertEquals(3L, alloc.shares.getValue(c).value)
        assertEquals(4L, alloc.shares.getValue(a).value)
    }
}
