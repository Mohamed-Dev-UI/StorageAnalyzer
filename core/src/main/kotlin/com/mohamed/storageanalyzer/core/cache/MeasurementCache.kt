package com.mohamed.storageanalyzer.core.cache

import com.mohamed.storageanalyzer.core.identity.ResourceIdentity
import com.mohamed.storageanalyzer.core.result.StorageMeasurement
import java.time.Duration
import java.time.Instant

/**
 * Optional measurement cache for incremental analysis.
 *
 * **Contract:**
 * - Cache keys include the metric set; two runs that ask for different
 *   metrics never share entries.
 * - Cached values must be *timestamped and auditable*: the analyzer records
 *   staleness in reports; a cached measurement's provenance measuredAt
 *   reflects the original measurement time.
 * - Content-change detection is NOT the cache's job. The library never
 *   treats file timestamps as proof that content is unchanged: callers
 *   invalidate explicitly via [invalidate][invalidate] after bulk operations,
 *   migrations, or when providers announce changes.
 * - The always-available correctness fallback is a plain (cache-disabled)
 *   full analysis.
 */
interface MeasurementCache {
    /** Returns a cached entry when present and not expired. */
    fun get(providerId: String, identity: ResourceIdentity, metrics: Set<String>): CachedMeasurement?

    fun put(providerId: String, identity: ResourceIdentity, metrics: Set<String>, value: StorageMeasurement)

    fun invalidate(providerId: String?, identity: ResourceIdentity?): Int

    fun clear()
}

/** A cache entry with freshness metadata maintained by the cache. */
data class CachedMeasurement(
    val measuredAt: Instant,
    val ttl: Duration?,
    val value: StorageMeasurement,
) {
    fun isFresh(now: Instant): Boolean = ttl == null || now.isBefore(measuredAt.plus(ttl))
}

/** A no-memory cache reliably used as the default (honest) fallback. */
class NullMeasurementCache : MeasurementCache {
    override fun get(providerId: String, identity: ResourceIdentity, metrics: Set<String>): CachedMeasurement? = null
    override fun put(providerId: String, identity: ResourceIdentity, metrics: Set<String>, value: StorageMeasurement) = Unit
    override fun invalidate(providerId: String?, identity: ResourceIdentity?): Int = 0
    override fun clear() = Unit
}

/** Simple bounded in-memory cache, single-provider safe (synchronized). */
class BindMeasureCache(maxSize: Int = 1024) : MeasurementCache {

    private val maxSize = maxSize.coerceAtLeast(16)
    private val entries = LinkedHashMap<String, CachedMeasurement>()

    private fun key(providerId: String, identity: ResourceIdentity, metrics: Set<String>) =
        providerId + "|" + identity.stableKey + "|" + metrics.sorted().joinToString(",")

    @Synchronized
    override fun get(providerId: String, identity: ResourceIdentity, metrics: Set<String>): CachedMeasurement? =
        entries[key(providerId, identity, metrics)]

    @Synchronized
    override fun put(providerId: String, identity: ResourceIdentity, metrics: Set<String>, value: StorageMeasurement) {
        if (entries.size >= maxSize) {
            entries.remove(entries.keys.first())
        }
        entries[key(providerId, identity, metrics)] = CachedMeasurement(
            measuredAt = value.measuredAt,
            ttl = null,
            value = value,
        )
    }

    @Synchronized
    override fun invalidate(providerId: String?, identity: ResourceIdentity?): Int {
        if (providerId == null && identity == null) {
            val n = entries.size
            entries.clear()
            return n
        }
        val toRemove = entries.entries.filter { (k, _) ->
            (providerId == null || k.startsWith(providerId + "|")) &&
                (identity == null || k.contains("|" + identity.stableKey + "|"))
        }
        toRemove.forEach { entries.remove(it.key) }
        return toRemove.size
    }

    @Synchronized
    override fun clear() = entries.clear()
}
