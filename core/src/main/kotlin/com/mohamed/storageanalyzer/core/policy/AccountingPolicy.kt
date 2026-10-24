package com.mohamed.storageanalyzer.core.policy

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.measurement.ByteCount
import com.mohamed.storageanalyzer.core.resource.OwnerRef

/**
 * Policy that decides how the physical size of a shared resource (one whose
 * size is claimed by more than one owner) is *attributed* to the owners.
 *
 * Allocation is strictly separate from measurement (see the accounting guide):
 * measurements remain untouched; policies only distribute already-measured
 * [ByteCount]s between owners. Policies must be deterministic — repeated runs
 * with identical inputs must allocate identically.
 */
interface AccountingPolicy {

    /** Stable, documented policy identifier (e.g. `canonical_owner`). */
    val policyId: String

    /**
     * Distributes [bytes] of the shared resource identified by [shared] among
     * the [owners].
     *
     * Contract:
     * - The sum of all shares plus [Allocation.unattributed] equals exactly
     *   [bytes] (no bytes invented or lost).
     * - Shares must be deterministic w.r.t. the order-free set of owners.
     */
    fun allocate(shared: ResourceIdentity, bytes: ByteCount, owners: List<OwnerRef>): Allocation
}

/** Result of attributing one shared resource among owners. */
data class Allocation(
    /** Ownership share of each owner; owners with zero share may be omitted. */
    val shares: Map<OwnerRef, ByteCount>,

    /** Bytes kept for accounting that cannot be attached to any owner. */
    val unattributed: ByteCount,
) {
    /** The effective policy reserved bytes for the resource (owned + fallback). */
    val allocated: ByteCount
        get() = ByteCount.sum(shares.values) + unattributed
}
