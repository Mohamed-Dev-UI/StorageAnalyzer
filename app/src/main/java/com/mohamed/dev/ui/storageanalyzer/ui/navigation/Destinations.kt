package com.mohamed.dev.ui.storageanalyzer.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector

enum class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Overview("overview", "Overview", Icons.Filled.Dashboard),
    Explorer("explorer", "Explorer", Icons.Filled.FolderOpen),
    History("history", "History", Icons.Filled.Timeline),
    Database("database", "Database", Icons.Filled.Storage),
    Lab("lab", "Lab", Icons.Filled.Science),
}

object Routes {
    const val GUIDE = "guide"
    const val RESOURCE = "resource/{stableKey}"
    const val STABLE_KEY_ARG = "stableKey"

    fun resource(stableKey: String): String = "resource/" + Uri.encode(stableKey)
}
