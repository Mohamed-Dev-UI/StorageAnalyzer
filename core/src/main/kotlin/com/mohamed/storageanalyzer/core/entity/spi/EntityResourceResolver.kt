package com.mohamed.storageanalyzer.core.entity.spi

import com.mohamed.storageanalyzer.core.config.AnalysisContext
import com.mohamed.storageanalyzer.core.entity.EntityDescriptor
import com.mohamed.storageanalyzer.core.resource.StorageResource

/**
 * SPI implemented by providers that can enumerate the storage resources that
 * back entities of a given descriptor (e.g. all rows of the descriptor's
 * table, all files referenced by a descriptor kind).
 */
interface EntityResourceResolver {
    /**
     * Streams the resources backing instances of [descriptor]. Implementations
     * must respect the context budgets and remain streaming.
     */
    fun resourcesFor(descriptor: EntityDescriptor, ctx: AnalysisContext): Sequence<StorageResource>
}
