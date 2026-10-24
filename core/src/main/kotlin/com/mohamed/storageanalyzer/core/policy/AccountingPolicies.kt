package com.mohamed.storageanalyzer.core.policy

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.resource.OwnerRef

/**
 * The built-in accounting policies (see `docs/ACCOUNTING.md` for a detailed
 * description, including when each is appropriate).
 */
object AccountingPolicies {

    /**
     * Every shared resource is counted exactly once in the global inventory,
     * and never attributed to an owner. This is the default global accounting
     * policy because it guarantees "never double count shared resources".
     */
    val COUNT_ONCE_GLOBALLY: AccountingPolicy = object : AccountingPolicy {
        override val policyId: String = "count_once_globally"
        override fun allocate(shared: ResourceIdentity, bytes: ByteCount, owners: List<OwnerRef>): Allocation =
            Allocation(shares = emptyMap(), unattributed = ByteCount.ZERO)
    }

    /**
     * A shared resource is entirely attributed to a single canonical owner:
     * the owner with the smallest [OwnerRef] id (deterministic).
     */
    val CANONICAL_OWNER: AccountingPolicy = object : AccountingPolicy {
        override val policyId: String = "canonical_owner"
        override fun allocate(shared: ResourceIdentity, bytes: ByteCount, owners: List<OwnerRef>): Allocation {
            require(owners.isNotEmpty()) { "CANONICAL_OWNER requires at least one owner" }
            val canonical = owners.min()
            val rest = owners.filter { it != canonical }
            return Allocation(
                shares = buildMap {
                    put(canonical, bytes)
                    for (o in rest) put(o, ByteCount.ZERO)
                },
                unattributed = ByteCount.ZERO,
            )
        }
    }

    /**
     * Split the bytes equally among the owners. Equal split distributes the
     * indivisible remainder bytes round-robin in owner-id order, so that no
     * byte goes missing.
     */
    val EQUAL_SPLIT: AccountingPolicy = object : AccountingPolicy {
        override val policyId: String = "equal_split"
        override fun allocate(shared: ResourceIdentity, bytes: ByteCount, owners: List<OwnerRef>): Allocation {
            require(owners.isNotEmpty()) { "EQUAL_SPLIT requires at least one owner" }
            val (per, remainder) = ByteCount.splitEqually(bytes, owners.size)
            val result = owners.associateWith { per }.toMutableMap()
            val remainderCount = remainder.value
            var counter = 0L
            // Iterate owners in stable (id) order and round-robin the remainder.
            val sorted = owners.sortedBy { it.id }
            for (i in 0 until remainderCount) {
                val owner = sorted[(counter % sorted.size).toInt()]
                counter++
                result[owner] = result.getValue(owner) + ByteCount.of(1)
            }
            return Allocation(shares = result, unattributed = ByteCount.ZERO)
        }
    }

    /**
     * Split the bytes proportionally to application-supplied weights; when a
     * resource has no weights available, falls back to [EQUAL_SPLIT].
     */
    class Proportional(
        /** Weighter — never null for known entity types (else `null` and fallback). */
        private val weights: (ResourceIdentity, List<OwnerRef>) -> Map<OwnerRef, Long>?,
    ) : AccountingPolicy {

        override val policyId: String = "proportional"
        override fun allocate(shared: ResourceIdentity, bytes: ByteCount, owners: List<OwnerRef>): Allocation {
            require(owners.isNotEmpty()) { "PROPORTIONAL requires at least one owner" }
            val w = weights(shared, owners)
            if (w == null || w.values.sum() <= 0) {
                return EQUAL_SPLIT.allocate(shared, bytes, owners).let { Allocation(it.shares, it.unattributed) }
            }
            require(w.keys.toSet() == owners.toSet()) { "proportional weights must offer owners" }
            val total = w.values.sum()
            require(total > 0) { "proportional weights must be positive" }
            val shares = buildMap {
                for (o in owners) {
                    val share = Math.multiplyExact(bytes.value, w.getValue(o)) / total
                    put(o, ByteCount.of(share))
                }
            }
            // Normalize: distribute remainder bytes floor-by-owner in id order.
            val allocated = shares.values.fold(ByteCount.ZERO, ByteCount::plus)
            val left = bytes.minus(allocated)
            val out = shares.toMutableMap()
            val sorted = owners.sortedBy { it.id }
            var idx = 0
            for (i in 0 until left.value) {
                val owner = sorted[(idx % sorted.size).toInt()]
                idx++
                out[owner] = out.getValue(owner) + ByteCount.of(1)
            }
            return Allocation(shares = out, unattributed = ByteCount.ZERO)
        }
    }
}
