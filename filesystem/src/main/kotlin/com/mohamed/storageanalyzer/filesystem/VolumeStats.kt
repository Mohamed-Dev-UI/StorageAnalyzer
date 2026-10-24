package com.mohamed.storageanalyzer.filesystem

/**
 * Volume-level space numbers supplied by the host.
 *
 * `java.nio` cannot resolve a [java.nio.file.FileStore] on every platform
 * (notably Android scoped storage), and some hosts have a more accurate
 * platform source. A resolver returning these values takes precedence over the
 * JVM store; returning `null` means "unknown for this root" and measurement
 * falls back to `FileStore`.
 *
 * Values are a snapshot in bytes.
 *
 * @property totalBytes size of the volume
 * @property freeBytes bytes available to this process (usable space)
 * @property reservedBytes bytes held back by the platform for system use, when known
 */
data class VolumeStats(
    val totalBytes: Long,
    val freeBytes: Long,
    val reservedBytes: Long? = null,
)
