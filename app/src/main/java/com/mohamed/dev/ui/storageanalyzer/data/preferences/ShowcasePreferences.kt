package com.mohamed.dev.ui.storageanalyzer.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small SharedPreferences-backed dataset that the custom provider measures.
 * The provider reads through [entries] so the demo stays live after edits.
 */
@Singleton
class ShowcasePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun entries(): Map<String, String> = prefs.all
        .mapNotNull { (key, value) -> value?.let { key to it.toString() } }
        .toMap()
        .toSortedMap()

    fun seedIfEmpty() {
        if (prefs.all.isNotEmpty()) return
        prefs.edit()
            .putString("feature_flags", "attachments=true;sync=wifi;compaction=nightly")
            .putString("theme.mode", "system")
            .putString("scanner.window", "documents,media,logs,sources")
            .putString("sync.endpoint", "https://sync.example.dev/v2")
            .putString("onboarding.version", "7")
            .apply()
    }

    companion object {
        const val NAME = "showcase_prefs"
        const val SHARED_ENTRY = "feature_flags"
    }
}
