package com.mohamed.storageanalyzer.history

import com.mohamed.storageanalyzer.core.snapshot.StorageSnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-based [SnapshotStore]: one canonical [SnapshotFormat] document per
 * snapshot inside [directory], named `%012d.snapshot` by 0-based index.
 *
 * Atomicity: each append writes a temp file in the same directory and then
 * moves it into place (REPLACE_EXISTING + ATOMIC_MOVE with a plain-move
 * fallback). A crash mid-append leaves an incomplete *temp* file behind,
 * never a truncated snapshot document.
 *
 * Read contract: documents are enumerated by index (== append order) and
 * decoded strictly; a corrupt document aborts with the offending file name —
 * partial histories are never passed off as complete ones.
 */
class FileSnapshotStore(private val directory: Path) : SnapshotStore {

    private val appendLock = Any()

    override fun append(snapshot: StorageSnapshot): Long {
        synchronized(appendLock) {
            Files.createDirectories(directory)
            val nextIndex = reserveIndex()
            val target = directory.resolve("%012d.snapshot".format(nextIndex))
            val temp = directory.resolve("%012d.snapshot.tmp".format(nextIndex))
            try {
                Files.writeString(temp, SnapshotFormat.encode(snapshot))
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    // ATOMIC_MOVE is unsupported on some file systems; fall back
                    // to the best-effort move (REPLACE_EXISTING is kept).
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
            return nextIndex.toLong()
        }
    }

    override fun readAll(): List<StorageSnapshot> = snapshotFiles().map { (index, file) ->
        try {
            SnapshotFormat.decode(Files.readString(file))
        } catch (e: Exception) {
            throw IOException(
                "corrupt snapshot file '$file' (index $index): ${e.message}; the store refuses to skip stored data silently",
                e,
            )
        }
    }

    override val size: Int
        get() = snapshotFiles().size

    /** Removes all stored documents (e.g. for retention policies). Returns the count. */
    fun clear(): Int {
        val files = snapshotFiles().map { it.second }
        files.forEach { Files.deleteIfExists(it) }
        return files.size
    }

    override fun close() {
        // No retained handles: every operation opens and closes.
    }

    // ------------------------------------------------------------ internals

    private fun reserveIndex(): Int {
        val files = snapshotFiles()
        val maxIndex = files.maxOfOrNull { it.first } ?: -1
        return maxIndex + 1
    }

    /** Sorted (index, file) pairs of complete (non-temp) snapshot documents. */
    private fun snapshotFiles(): List<Pair<Int, Path>> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.toList()
                .mapNotNull { path ->
                    val name = path.fileName.toString()
                    if (name.length == 21 && // "%012d" (12) + ".snapshot" (9)
                        name.endsWith(".snapshot") &&
                        name.substring(0, 12).all { it in '0'..'9' }
                    ) {
                        name.substring(0, 12).toInt() to path
                    } else {
                        null // temp files and foreign files are ignored for reading
                    }
                }
                .sortedBy { it.first }
        }
    }
}
