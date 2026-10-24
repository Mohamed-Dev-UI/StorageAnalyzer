package com.mohamed.storageanalyzer.core.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

class MetricTypeTest {

    @Test
    fun `built-in metric ids are stable`() {
        assertEquals("logical_content_size", MetricType.LOGICAL_CONTENT_SIZE.id)
        assertEquals("stored_content_size", MetricType.STORED_CONTENT_SIZE.id)
        assertEquals("file_allocated_size", MetricType.FILE_ALLOCATED_SIZE.id)
        assertEquals("database_page_capacity", MetricType.DATABASE_PAGE_CAPACITY.id)
    }

    @Test
    fun `custom metrics cannot collide with built-in ids`() {
        assertFailsWith<IllegalArgumentException> {
            MetricType.custom("stored_content_size", MetricDimension.LOGICAL)
        }
        assertEquals("x_provider.extra", MetricType.custom("x_provider.extra", MetricDimension.LOGICAL).id)
    }

    @Test
    fun `metrics are looked up by id`() {
        assertNotNull(MetricType.fromId("database_index_storage"))
        assertEquals(MetricType.DATABASE_INDEX_STORAGE, MetricType.fromId("database_index_storage"))
        assertFalse(MetricType.ALL.any { it.id.isEmpty() })
    }

    @Test
    fun `custom ids must be lowercase`() {
        assertFailsWith<IllegalArgumentException> {
            MetricType.custom("BAD_ID", MetricDimension.LOGICAL)
        }
    }
}
