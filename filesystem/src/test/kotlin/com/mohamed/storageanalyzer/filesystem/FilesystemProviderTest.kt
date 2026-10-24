package com.mohamed.storageanalyzer.filesystem

import com.mohamed.storageanalyzer.core.capability.StorageCapability
import com.mohamed.storageanalyzer.core.identity.PathIdentity
import com.mohamed.storageanalyzer.core.identity.ResourceKind
import com.mohamed.storageanalyzer.core.measurement.MeasurementAccuracy
import com.mohamed.storageanalyzer.core.measurement.MeasurementMethod
import com.mohamed.storageanalyzer.core.measurement.MeasurementState
import com.mohamed.storageanalyzer.core.measurement.MetricType
import com.mohamed.storageanalyzer.core.resource.RelationType
import com.mohamed.storageanalyzer.core.result.DiagnosticSeverity
import com.mohamed.storageanalyzer.core.result.StorageError
import com.mohamed.storageanalyzer.testing.RecordingContext
import com.mohamed.storageanalyzer.testing.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provider tests against the real local filesystem (temp dirs): identity
 * scheme, exact logical sizes, allocation rounding, volume facade, hard-link
 * dedup, symlink skipping, diagnostics and cancellation.
 */
class FilesystemProviderTest {

    // ------------------------------------------------------------- discovery

    @Test
    fun `discovers directories and files with stable path identities`() {
        TempDir("sa-fs").use { tmp ->
            val a = tmp.root.resolve("a"); Files.createDirectory(a)
            val sub = a.resolve("sub"); Files.createDirectory(sub)
            Files.write(a.resolve("f1.txt"), ByteArray(100))
            Files.write(sub.resolve("f2.bin"), ByteArray(7))

            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val resources = p.discoverResources(null, ctx).toList()

            val files = resources.filter { it.kind == ResourceKind.FILE }
            val dirs = resources.filter { it.kind == ResourceKind.DIRECTORY }
            assertEquals(2, files.size)
            assertEquals(3, dirs.size) // scan root + 'a' + 'a/sub'

            val f1 = files.first { it.label == "f1.txt" }
            assertEquals(PathIdentity.forChild(a.toFile(), "f1.txt", ResourceKind.FILE), f1.identity)
            assertNull(f1.identity.namespace)
            assertNull(f1.decomposes) // files carry their own bytes

            // the scan root is among the discovered directories
            assertTrue(dirs.any { it.identity == PathIdentity.forPath(tmp.root.toString(), ResourceKind.DIRECTORY) })
            p.close()
        }
    }

    @Test
    fun `file sizes are exact and volume capacity is reported on the root`() {
        TempDir("sa-fs").use { tmp ->
            val d = tmp.root.resolve("d"); Files.createDirectory(d)
            Files.write(tmp.root.resolve("data.txt"), ByteArray(1234))
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val resources = p.discoverResources(null, ctx).toList()
            val root = resources.first {
                it.kind == ResourceKind.DIRECTORY &&
                    it.identity == PathIdentity.forPath(tmp.root.toString(), ResourceKind.DIRECTORY)
            }
            val file = resources.first { it.kind == ResourceKind.FILE }

            val fm = p.measure(file.identity, emptySet(), ctx)
            assertEquals(1234L, fm.valueOf(MetricType.STORED_CONTENT_SIZE)!!.value)
            assertEquals(1234L, fm.valueOf(MetricType.FILE_LOGICAL_SIZE)!!.value)
            assertEquals(
                MeasurementAccuracy.EXACT,
                fm.measurements.getValue(MetricType.STORED_CONTENT_SIZE).provenance.accuracy,
            )

            val rm = p.measure(root.identity, setOf(MetricType.TOTAL_CAPACITY, MetricType.FREE_CAPACITY), ctx)
            val total = rm.valueOf(MetricType.TOTAL_CAPACITY)
            val free = rm.valueOf(MetricType.FREE_CAPACITY)
            assertNotNull(total)
            assertNotNull(free)
            assertEquals(Files.getFileStore(tmp.root).totalSpace, total.value)
            assertTrue(free.value > 0L)

            // nested directories do not claim volume numbers (volume facade)
            val nested = resources.first {
                it.kind == ResourceKind.DIRECTORY && it.identity != root.identity
            }
            val nm = p.measure(nested.identity, setOf(MetricType.TOTAL_CAPACITY), ctx)
            assertEquals(MeasurementState.NOT_APPLICABLE, nm.measurements.getValue(MetricType.TOTAL_CAPACITY).state)
            p.close()
        }
    }

    @Test
    fun `host volume stats take precedence and carry host provenance`() {
        TempDir("sa-fs").use { tmp ->
            val p = FilesystemProvider(
                listOf(tmp.root.toString()),
                volumeStatsResolver = { VolumeStats(totalBytes = 1_000_000, freeBytes = 400_000, reservedBytes = 50_000) },
            )
            val ctx = RecordingContext()
            val root = p.discoverResources(null, ctx).first {
                it.kind == ResourceKind.DIRECTORY &&
                    it.identity == PathIdentity.forPath(tmp.root.toString(), ResourceKind.DIRECTORY)
            }

            val m = p.measure(
                root.identity,
                setOf(MetricType.TOTAL_CAPACITY, MetricType.FREE_CAPACITY, MetricType.RESERVED_CAPACITY),
                ctx,
            )
            val total = m.measurements.getValue(MetricType.TOTAL_CAPACITY)
            assertEquals(MeasurementState.MEASURED, total.state)
            assertEquals(1_000_000L, total.value!!.value)
            assertEquals(400_000L, m.valueOf(MetricType.FREE_CAPACITY)!!.value)
            assertEquals(50_000L, m.valueOf(MetricType.RESERVED_CAPACITY)!!.value)
            assertEquals(MeasurementMethod.HOST_VOLUME_STATS, total.provenance.method)
            assertEquals(MeasurementAccuracy.EXACT, total.provenance.accuracy)
            assertTrue(FilesystemProvider.LIMIT_HOST_VOLUME_STATS in total.provenance.limitations)
            p.close()
        }
    }

    @Test
    fun `null host stats fall back to the FileStore`() {
        TempDir("sa-fs").use { tmp ->
            val p = FilesystemProvider(
                listOf(tmp.root.toString()),
                volumeStatsResolver = { null },
            )
            val ctx = RecordingContext()
            val root = p.discoverResources(null, ctx).first {
                it.kind == ResourceKind.DIRECTORY &&
                    it.identity == PathIdentity.forPath(tmp.root.toString(), ResourceKind.DIRECTORY)
            }
            val total = p.measure(root.identity, setOf(MetricType.TOTAL_CAPACITY), ctx)
                .measurements.getValue(MetricType.TOTAL_CAPACITY)
            assertEquals(MeasurementState.MEASURED, total.state)
            assertEquals(Files.getFileStore(tmp.root).totalSpace, total.value!!.value)
            assertEquals(MeasurementMethod.VOLUME_SPACE_STATS, total.provenance.method)
            p.close()
        }
    }

    @Test
    fun `incomplete or inconsistent host stats stay honest`() {
        TempDir("sa-fs").use { tmp ->
            val ctx = RecordingContext()
            fun rootOf(p: FilesystemProvider) = p.discoverResources(null, ctx).first {
                it.kind == ResourceKind.DIRECTORY &&
                    it.identity == PathIdentity.forPath(tmp.root.toString(), ResourceKind.DIRECTORY)
            }

            val noReserved = FilesystemProvider(
                listOf(tmp.root.toString()),
                volumeStatsResolver = { VolumeStats(totalBytes = 2_000, freeBytes = 500) },
            )
            val reserved = noReserved.measure(rootOf(noReserved).identity, setOf(MetricType.RESERVED_CAPACITY), ctx)
                .measurements.getValue(MetricType.RESERVED_CAPACITY)
            assertEquals(MeasurementState.UNAVAILABLE, reserved.state)
            assertTrue(reserved.reason.orEmpty().contains("reserved capacity"))
            noReserved.close()

            val inconsistent = FilesystemProvider(
                listOf(tmp.root.toString()),
                volumeStatsResolver = { VolumeStats(totalBytes = 100, freeBytes = 200) },
            )
            val total = inconsistent.measure(rootOf(inconsistent).identity, setOf(MetricType.TOTAL_CAPACITY), ctx)
                .measurements.getValue(MetricType.TOTAL_CAPACITY)
            assertEquals(MeasurementState.UNAVAILABLE, total.state)
            assertTrue(total.reason.orEmpty().contains("inconsistent"))
            inconsistent.close()

            val failing = FilesystemProvider(
                listOf(tmp.root.toString()),
                volumeStatsResolver = { error("StatFs unavailable") },
            )
            val failed = failing.measure(rootOf(failing).identity, setOf(MetricType.TOTAL_CAPACITY), ctx)
                .measurements.getValue(MetricType.TOTAL_CAPACITY)
            assertEquals(MeasurementState.UNAVAILABLE, failed.state)
            assertTrue(failed.reason.orEmpty().contains("host volume resolver failed"))
            failing.close()
        }
    }

    @Test
    fun `allocation rounding is convention-graded when a unit size is supplied`() {
        TempDir("sa-fs").use { tmp ->
            Files.write(tmp.root.resolve("x.txt"), ByteArray(100))
            val p = FilesystemProvider(
                listOf(tmp.root.toString()),
                allocationUnitResolver = { 4096L },
            )
            val ctx = RecordingContext()
            val file = p.discoverResources(null, ctx).first { it.kind == ResourceKind.FILE }
            val m = p.measure(file.identity, setOf(MetricType.FILE_ALLOCATED_SIZE), ctx)
            val allocated = m.measurements.getValue(MetricType.FILE_ALLOCATED_SIZE)
            assertEquals(MeasurementState.MEASURED, allocated.state)
            assertEquals(4096L, allocated.value!!.value)
            assertEquals(MeasurementAccuracy.CONVENTION, allocated.provenance.accuracy)
            assertTrue("fs.allocated.rounds-up" in allocated.provenance.limitations)
            assertTrue(p.capabilitiesFor(p.systems().single()).supports(StorageCapability.FILE_ALLOCATED_SIZE))
            p.close()
        }
    }

    @Test
    fun `no allocator means allocated size is honestly unavailable`() {
        TempDir("sa-fs").use { tmp ->
            Files.write(tmp.root.resolve("x.txt"), ByteArray(10))
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val file = p.discoverResources(null, ctx).first { it.kind == ResourceKind.FILE }
            val m = p.measure(file.identity, setOf(MetricType.FILE_ALLOCATED_SIZE), ctx)
            val allocated = m.measurements.getValue(MetricType.FILE_ALLOCATED_SIZE)
            assertEquals(MeasurementState.UNAVAILABLE, allocated.state)
            assertNull(allocated.value)
            assertTrue(allocated.reason.orEmpty().contains("allocation-unit"))
            p.close()
        }
    }

    // ------------------------------------------------------------- dedup

    @Test
    fun `hard links deduplicate via decomposition of the first-seen path`() {
        TempDir("sa-fs").use { tmp ->
            val original = tmp.root.resolve("orig.txt")
            Files.write(original, ByteArray(500))
            val link = tmp.root.resolve("alias.txt")
            try {
                Files.createLink(link, original)
            } catch (e: UnsupportedOperationException) {
                return // platform cannot create links; skip without failing the suite
            } catch (e: java.io.IOException) {
                return
            }
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val resources = p.discoverResources(null, ctx).filter { it.kind == ResourceKind.FILE }.toList()
            val fileKey = Files.readAttributes(original, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
            assertEquals(2, resources.size)
            if (fileKey == null) {
                // no platform inode info: both files must remain independent
                assertTrue(resources.all { it.decomposes == null })
            } else {
                val canonical = resources.first { it.decomposes == null }
                val dup = resources.first { it.decomposes != null }
                assertEquals(canonical.identity, dup.decomposes)
                // the measured sizes are still exact on the duplicate path
                assertEquals(500L, p.measure(dup.identity, setOf(MetricType.STORED_CONTENT_SIZE), ctx).valueOf(MetricType.STORED_CONTENT_SIZE)!!.value)
            }
            p.close()
        }
    }

    @Test
    fun `overlapping scan roots produce identical identities (dedup keys)`() {
        TempDir("sa-fs").use { tmp ->
            val sub = tmp.root.resolve("s"); Files.createDirectory(sub)
            Files.write(sub.resolve("f.txt"), ByteArray(42))
            val p = FilesystemProvider(listOf(tmp.root.toString(), sub.toString()))
            assertEquals(2, p.systems().size)
            val ctx = RecordingContext()
            val f = p.discoverResources(null, ctx).filter { it.kind == ResourceKind.FILE && it.label == "f.txt" }.toList()
            assertEquals(2, f.size) // discovered from both systems...
            assertEquals(1, f.distinctBy { it.identity }.size) // ...under one stable key
            p.close()
        }
    }

    // ------------------------------------------------------------- symlinks

    @Test
    fun `symlinks inside the tree are detected and skipped`() {
        TempDir("sa-fs").use { tmp ->
            Files.write(tmp.root.resolve("real.txt"), ByteArray(5))
            try {
                Files.createSymbolicLink(tmp.root.resolve("link.lnk"), tmp.root.resolve("real.txt"))
            } catch (e: Exception) {
                return // needs privileges on Windows dev machines; skip otherwise
            }
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val resources = p.discoverResources(null, ctx).toList()
            assertTrue(resources.none { it.label == "link.lnk" })
            assertTrue(ctx.diagnostics.any { it.code == FilesystemProvider.DIAG_SYMLINK_SKIPPED })
            p.close()
        }
    }

    // ------------------------------------------------------------- relations

    @Test
    fun `directories expose containment edges lazily`() {
        TempDir("sa-fs").use { tmp ->
            val a = tmp.root.resolve("a"); Files.createDirectory(a)
            Files.write(a.resolve("inside.txt"), ByteArray(3))
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val dirA = p.discoverResources(null, ctx).first {
                it.kind == ResourceKind.DIRECTORY && it.label == "a"
            }
            val edges = p.related(dirA.identity, ctx)
            assertEquals(1, edges.size)
            assertEquals(RelationType.CONTAINS, edges.single().relation)
            assertEquals("inside.txt", edges.single().toIdentity.nativeId)
            p.close()
        }
    }

    // ------------------------------------------------------------- diagnostics

    @Test
    fun `missing root fails health check and yields no resources`() {
        val p = FilesystemProvider(listOf("Z:\\definitely\\not\\a\\root"))
        val ctx = RecordingContext()
        assertTrue(p.healthCheck(ctx).any { it.code == "fs.root_missing" && it.severity == DiagnosticSeverity.ERROR })
        assertTrue(p.discoverResources(null, ctx).toList().isEmpty())
        p.close()
    }

    @Test
    fun `vanished files measure as unavailable without zeros`() {
        TempDir("sa-fs").use { tmp ->
            val f = tmp.root.resolve("ghost.txt")
            Files.write(f, ByteArray(9))
            val identity = PathIdentity.forPath(f.toString())
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            f.toFile().delete()
            val m = p.measure(identity, emptySet(), ctx)
            assertTrue(m.measurements.isNotEmpty())
            assertTrue(m.measurements.values.all { it.state == MeasurementState.UNAVAILABLE && it.value == null })
            p.close()
        }
    }

    @Test
    fun `cancellation aborts discovery`() {
        TempDir("sa-fs").use { tmp ->
            val a = tmp.root.resolve("a"); Files.createDirectory(a)
            Files.write(a.resolve("f.txt"), ByteArray(1))
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            ctx.cancelled = true
            var threw = false
            try {
                p.discoverResources(null, ctx).toList()
            } catch (e: StorageError.Cancelled) {
                threw = true
            }
            assertTrue(threw)
            p.close()
        }
    }

    // ------------------------------------------------------------- categories

    @Test
    fun `categories follow the documented extension map`() {
        TempDir("sa-fs").use { tmp ->
            listOf("app.log", "photo.png", "arch.zip", "run.exe", "x.db", "plain.zzz").forEach {
                Files.write(tmp.root.resolve(it), ByteArray(1))
            }
            val p = FilesystemProvider(listOf(tmp.root.toString()))
            val ctx = RecordingContext()
            val byName = p.discoverResources(null, ctx)
                .filter { it.kind == ResourceKind.FILE }
                .associate { it.label!! to it.category }
            assertEquals("LOGS", byName["app.log"]?.name)
            assertEquals("MEDIA", byName["photo.png"]?.name)
            assertEquals("ARCHIVE", byName["arch.zip"]?.name)
            assertEquals("EXECUTABLE", byName["run.exe"]?.name)
            assertEquals("DATABASE", byName["x.db"]?.name)
            assertEquals("OTHER", byName["plain.zzz"]?.name)
            p.close()
        }
    }
}
