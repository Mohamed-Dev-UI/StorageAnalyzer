package com.mohamed.dev.ui.storageanalyzer.data.demo

import java.io.File

/**
 * Locations the showcase analyzes. Both are app-specific directories, so no
 * runtime permission is required on any supported API level.
 */
data class DemoEnvironment(
    val root: File,
    val databaseDir: File,
) {
    val rootPath: String get() = root.canonicalPath
    val databasePath: String get() = File(databaseDir, "showcase.db").canonicalPath
}
