package com.mohamed.storageanalyzer.core.provider

import com.mohamed.storageanalyzer.core.identity.ProviderId
import com.mohamed.storageanalyzer.core.result.StorageError

/**
 * Deterministic registry of providers. Iteration order is sorted by
 * [ProviderId] so that inventory composition is deterministic across runs.
 */
class StorageProviderRegistry {

    private val providers = LinkedHashMap<String, StorageProvider>()

    /** Registers [provider]; fails fast on id duplicates. */
    fun register(provider: StorageProvider) {
        val id = provider.providerId.value
        check(!providers.containsKey(id)) {
            "Provider id '$id' is already registered (${providers[id]!!::class.simpleName})"
        }
        providers[id] = provider
    }

    fun unregister(providerId: String): Boolean = providers.remove(providerId) != null

    fun first(providerId: ProviderId): StorageProvider? = providers[providerId.value]

    fun all(): List<StorageProvider> = providers.values.sortedBy { it.providerId.value }

    fun size(): Int = providers.size

    /** Closes every provider in deterministic order, collecting failures. */
    fun closeAll() {
        val failures = mutableListOf<Pair<String, Throwable>>()
        for (p in all()) {
            try {
                p.close()
            } catch (t: Throwable) {
                failures += p.providerId.value to t
            }
        }
        if (failures.isNotEmpty()) {
            throw StorageErrorInternal(failures)
        }
    }

    private class StorageErrorInternal(val failures: List<Pair<String, Throwable>>) :
        RuntimeException("failed to close ${failures.size} provider(s): " + failures.joinToString { (id, t) -> "$id(${t.message ?: t.javaClass.simpleName})" })
}
