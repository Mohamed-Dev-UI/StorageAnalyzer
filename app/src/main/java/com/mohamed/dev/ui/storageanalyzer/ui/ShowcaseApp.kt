package com.mohamed.dev.ui.storageanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mohamed.dev.ui.storageanalyzer.data.BootstrapState
import com.mohamed.dev.ui.storageanalyzer.ui.components.ActionButton
import com.mohamed.dev.ui.storageanalyzer.ui.components.ErrorBanner
import com.mohamed.dev.ui.storageanalyzer.ui.database.DatabaseScreen
import com.mohamed.dev.ui.storageanalyzer.ui.explorer.ExplorerScreen
import com.mohamed.dev.ui.storageanalyzer.ui.explorer.ResourceDetailScreen
import com.mohamed.dev.ui.storageanalyzer.ui.guide.GuideScreen
import com.mohamed.dev.ui.storageanalyzer.ui.history.HistoryScreen
import com.mohamed.dev.ui.storageanalyzer.ui.lab.LabScreen
import com.mohamed.dev.ui.storageanalyzer.ui.navigation.Routes
import com.mohamed.dev.ui.storageanalyzer.ui.navigation.TabDestination
import com.mohamed.dev.ui.storageanalyzer.ui.overview.OverviewScreen

@Composable
fun ShowcaseApp(viewModel: ShowcaseViewModel = hiltViewModel()) {
    val bootstrap by viewModel.bootstrap.collectAsStateWithLifecycle()
    when (val state = bootstrap) {
        is BootstrapState.Working -> BootstrapScreen(state.step)
        is BootstrapState.Failed -> BootstrapFailure(state.message, viewModel::retry)
        BootstrapState.Ready -> ShowcaseScaffold()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowcaseScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tab = TabDestination.entries.firstOrNull { it.route == currentRoute }
    val isGuide = currentRoute == Routes.GUIDE
    val isDetail = currentRoute == Routes.RESOURCE

    val title = when {
        tab != null -> tab.label
        isGuide -> "Implementation guide"
        isDetail -> "Resource detail"
        else -> "Storage Analyzer"
    }
    val subtitle = when {
        tab != null -> tabSubtitle(tab)
        isGuide -> "How the showcase consumes the library"
        isDetail -> "One resource, every metric and its provenance"
        else -> "Honest storage analytics"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (isGuide || isDetail) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (!isGuide) {
                        IconButton(onClick = { navController.navigate(Routes.GUIDE) }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Implementation guide")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (tab != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TabDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == tab,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TabDestination.Overview.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TabDestination.Overview.route) {
                OverviewScreen(
                    onOpenExplorer = { navController.navigate(TabDestination.Explorer.route) },
                    onOpenHistory = { navController.navigate(TabDestination.History.route) },
                    onOpenGuide = { navController.navigate(Routes.GUIDE) },
                )
            }
            composable(TabDestination.Explorer.route) {
                ExplorerScreen(
                    onOpenResource = { stableKey ->
                        navController.navigate(Routes.resource(stableKey))
                    },
                )
            }
            composable(TabDestination.History.route) { HistoryScreen() }
            composable(TabDestination.Database.route) { DatabaseScreen() }
            composable(TabDestination.Lab.route) { LabScreen() }
            composable(Routes.GUIDE) { GuideScreen() }
            composable(
                route = Routes.RESOURCE,
                arguments = listOf(navArgument(Routes.STABLE_KEY_ARG) { type = NavType.StringType }),
            ) {
                ResourceDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun tabSubtitle(tab: TabDestination): String = when (tab) {
    TabDestination.Overview -> "Unique footprint, capacity and live monitoring"
    TabDestination.Explorer -> "Resources, folders and per-resource provenance"
    TabDestination.History -> "Snapshots, comparison and growth forecast"
    TabDestination.Database -> "Room notes, WAL and page accounting"
    TabDestination.Lab -> "Budgets, cancellation and accounting experiments"
}

@Composable
private fun BootstrapScreen(step: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        Icons.Filled.Storage,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(18.dp)
                            .size(40.dp),
                    )
                }
                Text(
                    "Storage Analyzer",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "A working showcase of the storage analytics library: honest measurements, " +
                        "typed provenance and Room-backed history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    step,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BootstrapFailure(message: String, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("The demo could not start", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Seeding, the baseline snapshot or the first analysis failed. The error is shown " +
                    "verbatim instead of being replaced by empty data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            ErrorBanner(message = message, actionLabel = null)
            ActionButton(
                label = "Retry bootstrap",
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
