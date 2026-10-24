package com.mohamed.dev.ui.storageanalyzer.data.provider

import android.os.StatFs
import com.mohamed.storageanalyzer.filesystem.VolumeStats
import java.nio.file.Path

/**
 * Android's scoped-storage NIO cannot resolve a FileStore for the app's
 * private directory, so the library's volume facade would report capacity as
 * UNAVAILABLE. StatFs is the platform source for the same numbers.
 */
internal fun androidVolumeStats(path: Path): VolumeStats? = runCatching {
    val stat = StatFs(path.toString())
    VolumeStats(
        totalBytes = stat.totalBytes,
        freeBytes = stat.availableBytes,
        reservedBytes = (stat.freeBytes - stat.availableBytes).takeIf { it > 0L },
    )
}.getOrNull()
