package com.mohamed.dev.ui.storageanalyzer.data.demo

import com.mohamed.dev.ui.storageanalyzer.data.preferences.ShowcasePreferences
import com.mohamed.dev.ui.storageanalyzer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cheap change fingerprint for `RefreshPolicy.OnChange`: relative path,
 * length and mtime of every demo file, the Room database and its WAL/SHM
 * sidecars, plus a hash of the preference values.
 *
 * This is intentionally a *probe*, not an analysis: it walks a small tree and
 * never measures content. A probe failure returns `null`, which the refresh
 * policy treats as "unknown" and therefore triggers a fresh turn.
 */
@Singleton
class DemoFingerprint @Inject constructor(
    private val environment: DemoEnvironment,
    private val preferences: ShowcasePreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun probe(): String? = withContext(io) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val builder = StringBuilder()

            environment.root.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(environment.root).invariantSeparatorsPath }
                .forEach { file ->
                    builder.append(file.relativeTo(environment.root).invariantSeparatorsPath)
                        .append(':').append(file.length())
                        .append(':').append(file.lastModified())
                        .append(';')
                }

            for (name in listOf("showcase.db", "showcase.db-wal", "showcase.db-shm")) {
                val file = File(environment.databaseDir, name)
                builder.append(name).append(':').append(file.length()).append(':')
                    .append(file.lastModified()).append(';')
            }
            preferences.entries().forEach { (key, value) ->
                builder.append(key).append('=').append(value.hashCode()).append(';')
            }

            digest.digest(builder.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
