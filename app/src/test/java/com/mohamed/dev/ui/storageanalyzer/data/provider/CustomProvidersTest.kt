package com.mohamed.dev.ui.storageanalyzer.data.provider

import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.OwnerRef
import com.mohamed.storageanalyzer.core.resource.StorageCategory
import com.mohamed.storageanalyzer.core.result.Completeness
import com.mohamed.storageanalyzer.testing.RecordingContext
import com.mohamed.storageanalyzer.testing.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two custom providers are the library's SPI example in executable form:
 * honest metric states, stable identities, explicit owners and cross-provider
 * alias declarations.
 */
class CustomProvidersTest {

    // ------------------------------------------------------------ archive catalog

    @Test
    fun `archive catalog aliases the filesystem identity and measures only payload size`() {
        TempDir("archive-catalog").use { tmp ->
            val zip = tmp.file("legacy-backup.zip", ByteArray(512))
            val provider = ArchiveCatalogProvider(zip.toFile())
            val ctx = RecordingContext()

            val resource = provider.discoverResources(null, ctx).single()

            assertEquals(ResourceKind.ARCHIVE, resource.kind)
            assertEquals(StorageCategory.ARCHIVE, resource.category)
            assertEquals(
                setOf(PathIdentity.forPath(zip.toString(), ResourceKind.FILE)),
                resource.alsoDiscoverableBy,
            )

            val measurement = provider.measure(
                resource.identity,
                setOf(MetricType.STORED_CONTENT_SIZE, MetricType.FILE_LOGICAL_SIZE),
                ctx,
            )

            val stored = measurement.measurements.getValue(MetricType.STORED_CONTENT_SIZE)
            assertEquals(MeasurementState.MEASURED, stored.state)
            assertEquals(512L, stored.value?.value)
            assertEquals(MeasurementAccuracy.EXACT, stored.provenance.accuracy)

            val logical = measurement.measurements.getValue(MetricType.FILE_LOGICAL_SIZE)
            assertEquals(MeasurementState.UNAVAILABLE, logical.state)
            assertTrue(logical.reason.orEmpty().contains("catalog"))
            assertEquals(Completeness.COMPLETE, measurement.completeness)
        }
    }

    @Test
    fun `archive catalog reports disappearance through state, completeness and health check`() {
        TempDir("archive-catalog-missing").use { tmp ->
            val zip = tmp.file("legacy-backup.zip", ByteArray(16))
            val provider = ArchiveCatalogProvider(zip.toFile())
            val ctx = RecordingContext()
            val identity = provider.discoverResources(null, ctx).single().identity

            assertTrue(zip.toFile().delete())

            val measurement = provider.measure(identity, setOf(MetricType.STORED_CONTENT_SIZE), ctx)
            assertEquals(MeasurementState.UNAVAILABLE, measurement.measurements.values.single().state)
            assertEquals(Completeness.PARTIAL, measurement.completeness)
            assertTrue(provider.healthCheck(ctx).any { it.code == "x_archive_catalog.missing_archive" })

            assertTrue(provider.discoverResources(null, RecordingContext()).none())
        }
    }

    // ---------------------------------------------------------------- preferences

    @Test
    fun `preferences provider measures encoded entries and marks gaps unavailable`() {
        var entries = sortedMapOf(
            "feature_flags" to "attachments=true;sync=wifi",
            "theme.mode" to "system",
        )
        val provider = ShowcasePreferencesProvider { entries }
        val ctx = RecordingContext()

        val resources = provider.discoverResources(null, ctx).toList()
        assertEquals(listOf("feature_flags", "theme.mode"), resources.map { it.identity.nativeId })
        assertEquals(1, provider.discoverSystems().size)
        assertTrue(provider.healthCheck(ctx).isEmpty())

        val shared = resources.first { it.identity.nativeId == "feature_flags" }
        assertEquals(
            listOf("scope:onboarding", "scope:sync"),
            shared.owners.map(OwnerRef::id),
        )

        val measurement = provider.measure(
            shared.identity,
            setOf(
                MetricType.STORED_CONTENT_SIZE,
                MetricType.ENCODED_CONTENT_SIZE,
                MetricType.FILE_LOGICAL_SIZE,
            ),
            ctx,
        )

        val expected = "feature_flags".length + 1 + entries.getValue("feature_flags").length
        assertEquals(expected.toLong(), measurement.valueOf(MetricType.STORED_CONTENT_SIZE)?.value)
        assertEquals(expected.toLong(), measurement.valueOf(MetricType.ENCODED_CONTENT_SIZE)?.value)
        assertEquals(
            MeasurementState.UNAVAILABLE,
            measurement.measurements.getValue(MetricType.FILE_LOGICAL_SIZE).state,
        )
        assertEquals("preferences", measurement.scope?.id)
        assertEquals(2, measurement.owners.size)
        assertEquals(
            MeasurementAccuracy.CONVENTION,
            measurement.measurements.getValue(MetricType.STORED_CONTENT_SIZE).provenance.accuracy,
        )
    }

    @Test
    fun `an entry removed after discovery stays visible as unavailable`() {
        var entries = sortedMapOf("theme.mode" to "system")
        val provider = ShowcasePreferencesProvider { entries }
        val ctx = RecordingContext()
        val identity = provider.discoverResources(null, ctx).single().identity

        entries = sortedMapOf()

        val measurement = provider.measure(identity, setOf(MetricType.STORED_CONTENT_SIZE), ctx)
        val single = measurement.measurements.values.single()
        assertEquals(MeasurementState.UNAVAILABLE, single.state)
        assertTrue(single.reason.orEmpty().contains("removed after discovery"))
        assertEquals(Completeness.PARTIAL, measurement.completeness)

        val warning = provider.healthCheck(ctx).single()
        assertEquals("x_showcase_prefs.empty", warning.code)
        assertNotNull(warning.providerId)
    }
}
