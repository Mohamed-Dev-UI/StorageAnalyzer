package com.mohamed.dev.ui.storageanalyzer.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.storageanalyzer.ui.components.BadgeTone
import com.mohamed.dev.ui.storageanalyzer.ui.components.BulletRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.CodeBlock
import com.mohamed.dev.ui.storageanalyzer.ui.components.InfoBanner
import com.mohamed.dev.ui.storageanalyzer.ui.components.KeyValueRow
import com.mohamed.dev.ui.storageanalyzer.ui.components.SectionCard
import com.mohamed.dev.ui.storageanalyzer.ui.components.ToneBadge

@Composable
fun GuideScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { IntroCard() }
        item { ArchitectureCard() }
        item { CoverageCard() }
        item { HonestyCard() }
        item { PatternsCard() }
        item { OptionsCard() }
        item { SnippetsCard() }
        item { JdbcCaveatCard() }
        item { TestingCard() }
        item { ModuleMapCard() }
    }
}

@Composable
private fun IntroCard() {
    SectionCard(
        title = "This app is executable documentation",
        subtitle = "Every screen calls the public library API directly",
    ) {
        Text(
            "The showcase is deliberately a normal modern app: Hilt for dependency injection, Room + KSP for " +
                "persistence, Compose/Material 3 for UI, coroutines and Flow for work, and a clean data layer between " +
                "the screens and the analyzer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoBanner(
            "Nothing on the screens is mocked. When a provider cannot measure a metric, the UI renders UNAVAILABLE " +
                "with the provider's own reason.",
        )
        Text(
            "Use the bottom navigation for the five working surfaces and this guide for the reasoning, the coverage " +
                "map, recommended patterns and the honest limits of the demo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchitectureCard() {
    SectionCard(title = "Architecture") {
        BulletRow("Compose screens observe ViewModels; ViewModels call repositories; only repositories touch the analyzer.")
        BulletRow("StorageRepository owns one long-lived StorageAnalyzer and serializes manual runs with a Mutex.")
        BulletRow("A second analyzer instance backs ReactiveAnalyzer, so live monitoring never races an interactive scan.")
        BulletRow("Room stores demo notes, scan sessions and encoded history snapshots. HistoryRepository decodes them for the timeline.")
        BulletRow("Hilt provides the analyzer, database, DAOs, dispatchers, application scope and custom providers.")
        CodeBlock(
            """
UI (Compose)
  -> ViewModel (StateFlow)
    -> Repository (IO dispatcher, Mutex)
      -> StorageAnalyzer
        -> FilesystemProvider
        -> ArchiveCatalogProvider (custom SPI)
        -> ShowcasePreferencesProvider (custom SPI)
      -> Room (notes, sessions, snapshots)

ReactiveAnalyzer -> fingerprint probe -> fresh analysis
            """.trimIndent(),
        )
        KeyValueRow("Package root", "com.mohamed.dev.ui.storageanalyzer", mono = true)
        KeyValueRow("DI", "Hilt 2.60.1 + KSP")
        KeyValueRow("Persistence", "Room 2.8.5, schema exported")
        KeyValueRow("UI", "Compose Material 3, Navigation Compose")
    }
}

@Composable
private fun CoverageCard() {
    SectionCard(
        title = "Scenario coverage",
        subtitle = "The library's usage guide is organized as scenarios A–L",
    ) {
        CoverageRow(
            code = "A",
            title = "What is eating the user's disk?",
            where = "Overview, Explorer, resource detail",
            status = "Runs in app",
            note = "Global report, category/provider breakdowns, per-resource provenance.",
        )
        CoverageRow(
            code = "B",
            title = "Inside a SQLite database",
            where = "JVM tests + this guide",
            status = "JVM only",
            note = "`:sqlite` and `:room` use JDBC, which is not safe as an Android dependency at minSdk 28.",
        )
        CoverageRow(
            code = "C",
            title = "Heaviest tables, rows and columns",
            where = "JVM tests + this guide",
            status = "JVM only",
            note = "Record-level analytics is exercised against the app's exported Room schema.",
        )
        CoverageRow(
            code = "D",
            title = "Everything at once, no double counting",
            where = "Overview providers + Lab dedup",
            status = "Runs in app",
            note = "Archive catalog overlaps are measured and shown, but never added to the unique footprint.",
        )
        CoverageRow(
            code = "E",
            title = "Track storage growth over time",
            where = "History",
            status = "Runs in app",
            note = "SnapshotStore over Room, comparison, attribution and linear forecast.",
        )
        CoverageRow(
            code = "F",
            title = "Account storage per application entity",
            where = "Explorer entity analytics",
            status = "Runs in app",
            note = "Each demo folder becomes an EntityDescriptor with five accounting views.",
        )
        CoverageRow(
            code = "G",
            title = "Responsive scans, budgets and cancellation",
            where = "Lab",
            status = "Runs in app",
            note = "A 12-resource budget yields PARTIAL completeness; cancellation is observed as a typed error.",
        )
        CoverageRow(
            code = "H",
            title = "Privacy, exclusions and provider targeting",
            where = "Lab",
            status = "Runs in app",
            note = "Exclusion predicates, label redaction and a filesystem-only run.",
        )
        CoverageRow(
            code = "I",
            title = "Health checks of stored data",
            where = "Lab diagnostics",
            status = "Runs in app",
            note = "Provider health checks plus descriptor registration checks.",
        )
        CoverageRow(
            code = "J",
            title = "Writing your own provider",
            where = "ArchiveCatalogProvider, ShowcasePreferencesProvider",
            status = "Runs in app",
            note = "Custom discovery, measurement, owners, capabilities and overlap declarations.",
        )
        CoverageRow(
            code = "K",
            title = "Room database investigation",
            where = "Database screen + JVM tests",
            status = "Partial",
            note = "The app demonstrates androidx Room. The library's JDBC `:room` provider is verified in JVM tests.",
        )
        CoverageRow(
            code = "L",
            title = "Live monitoring",
            where = "Overview",
            status = "Runs in app",
            note = "ReactiveAnalyzer with RefreshPolicy.OnChange and a cheap fingerprint probe.",
        )
    }
}

@Composable
private fun CoverageRow(
    code: String,
    title: String,
    where: String,
    status: String,
    note: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToneBadge(code, BadgeTone.Primary)
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            ToneBadge(status, coverageTone(status))
        }
        Text(
            where,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun HonestyCard() {
    SectionCard(title = "The five rules the UI obeys") {
        BulletRow("Unique means unique. The headline footprint counts every identity once; provider views and row sums are explicitly labelled as views.")
        BulletRow("A missing value is a state. UNAVAILABLE, NOT_APPLICABLE and NOT_YET_MEASURED are rendered with their reason, never as 0.")
        BulletRow("Every measured number carries provenance: method, accuracy, backend, limitations and optional uncertainty.")
        BulletRow("Completeness is part of the result. A budget or cancellation produces PARTIAL coverage, not a hidden guess.")
        BulletRow("Shared resources need a policy. The same bytes can be counted once, assigned to a canonical owner, or split equally; totals are conserved.")
    }
}

@Composable
private fun PatternsCard() {
    SectionCard(title = "Recommended integration patterns") {
        BulletRow("Build one analyzer per app process and keep it alive. Queries are read-only; the provider registry is the mutable part.")
        BulletRow("Run analysis and measurement on Dispatchers.IO. The app injects the dispatcher through Hilt so tests can replace it.")
        BulletRow("Bridge platform APIs through host resolvers: allocationUnitResolver for cluster size and volumeStatsResolver for capacity (StatFs on Android). Host-supplied volume numbers keep their own method tag and limitation.")
        BulletRow("Serialize expensive runs with a Mutex if the UI can trigger them from several places.")
        BulletRow("Keep live monitoring on its own analyzer instance. Use a cheap probe (metadata, not content) and a sane poll interval.")
        BulletRow("Capture snapshots with includeResourceDetails = true when you intend to do attribution later.")
        BulletRow("Persist snapshots with your own store if the library's file store is not safe at your minSdk; the SnapshotStore contract is synchronous, so bridge carefully off the main thread.")
        BulletRow("Register entity descriptors when you need concept-level attribution; folder entities work through provider relations even without custom relations.")
        BulletRow("Put provider ids in a stable namespace. The registry sorts by id, which determines composition order for overlaps.")
    }
}

@Composable
private fun OptionsCard() {
    SectionCard(
        title = "Options: what is honored today",
        subtitle = "Verified against the library source, not assumed",
    ) {
        Text("Honored", style = MaterialTheme.typography.titleSmall)
        BulletRow("metrics selects the requested metric set; unavailable metrics are still reported per resource.")
        BulletRow("budget limits resources/depth and produces PARTIAL completeness.")
        BulletRow("accountingPolicy controls shared-resource attribution while preserving the unique total.")
        BulletRow("excluded filters identities before measurement; providerFilter limits the provider set.")
        BulletRow("redactLabels replaces human labels in reports; stable keys remain intact.")
        BulletRow("includeRelations, includeUnavailableResources and includeIndexes affect the requested shape where the provider supports them.")
        Text("Declared but not consumed by the current core", style = MaterialTheme.typography.titleSmall)
        BulletRow("categories, includeFreeSpace and disableCaches are present on AnalysisOptions but are not read by the analyzer pipeline.")
        InfoBanner(
            "The showcase does not pretend those switches do something. They are listed here so consumers can plan " +
                "around the real behavior instead of discovering it in production.",
        )
    }
}

@Composable
private fun SnippetsCard() {
    SectionCard(title = "The patterns in code") {
        Text("Global analysis", style = MaterialTheme.typography.titleSmall)
        CodeBlock(
            """
val analyzer = StorageAnalyzer(
    config = AnalyzerConfig(footprintMetric = MetricType.STORED_CONTENT_SIZE),
    providers = listOf(filesystem, archiveCatalog, preferencesProvider),
)

val options = AnalysisOptions(
    metrics = setOf(STORED_CONTENT_SIZE, FILE_LOGICAL_SIZE, FILE_ALLOCATED_SIZE),
    budget = AnalysisBudget(maxResources = 5_000),
)

val report = analyzer.analyzeGlobal(options)
val uniqueBytes = report.totals.uniqueFootprint
            """.trimIndent(),
        )
        Text("Typed states", style = MaterialTheme.typography.titleSmall)
        CodeBlock(
            """
val measurement = analyzer.analyzeResource(identity, options)
for ((metric, single) in measurement.measurements) {
    when (single.state) {
        MEASURED -> show(single.value!!, single.provenance)
        UNAVAILABLE -> showReason(single.reason)
        NOT_APPLICABLE -> showNotApplicable()
        NOT_YET_MEASURED -> showPending()
    }
}
            """.trimIndent(),
        )
        Text("Snapshot store", style = MaterialTheme.typography.titleSmall)
        CodeBlock(
            """
@Singleton
class RoomSnapshotStore @Inject constructor(
    private val dao: HistorySnapshotDao,
    private val codec: SnapshotCodec,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SnapshotStore {
    @Synchronized
    override fun append(snapshot: StorageSnapshot): Long =
        runBlocking(io) { dao.insert(codec.toEntity(snapshot)) }
    // readAll / latest / size follow the same bridge
}
            """.trimIndent(),
        )
        Text("Volume stats on Android", style = MaterialTheme.typography.titleSmall)
        CodeBlock(
            """
fun androidVolumeStats(path: Path): VolumeStats? = runCatching {
    val stat = StatFs(path.toString())
    VolumeStats(
        totalBytes = stat.totalBytes,
        freeBytes = stat.availableBytes,
        reservedBytes = (stat.freeBytes - stat.availableBytes).takeIf { it > 0L },
    )
}.getOrNull()

val filesystem = FilesystemProvider(
    roots = listOf(filesDir.absolutePath),
    allocationUnitResolver = { 4096L },
    volumeStatsResolver = ::androidVolumeStats,
)
            """.trimIndent(),
        )
        Text("Live monitoring", style = MaterialTheme.typography.titleSmall)
        CodeBlock(
            """
val reactive = ReactiveAnalyzer(
    analyzer = liveAnalyzer,
    scope = applicationScope,
    policy = RefreshPolicy.OnChange(
        probe = { fingerprint.probe() },
        pollInterval = Duration.ofSeconds(3),
    ),
    options = scanOptions,
    dispatcher = io,
)
reactive.start()
            """.trimIndent(),
        )
    }
}

@Composable
private fun JdbcCaveatCard() {
    SectionCard(
        title = "Why SQLite/Room library modules are not in the APK",
        subtitle = "A deliberate platform boundary, not an omission",
    ) {
        Text(
            "`:sqlite` inspects database files through JDBC with sqlite-jdbc. `:room` layers provider and entity " +
                "analytics on top of it. JDBC is a JVM API surface that is not appropriate to ship into an Android app " +
                "with minSdk 28, so the showcase keeps the APK on the filesystem/history/reactive path.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The Database screen shows a different but honest thing: androidx Room reading its own SQLite file " +
                "through Android's SQLite, including page counts and WAL bytes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BulletRow("JVM tests open the app's exported schema with the library's Room/SQLite providers and assert measurement states and provenance.")
        BulletRow("The tests use sqlite-jdbc on the host JVM, where JDBC is a normal dependency.")
        BulletRow("If you need JDBC inspection on Android, evaluate a compatible driver and Android API support explicitly; this app does not pretend it is free.")
    }
}

@Composable
private fun TestingCard() {
    SectionCard(title = "How the showcase is tested") {
        BulletRow("JVM tests for the app-owned SnapshotCodec: round-trip, missing header and corrupt field line numbers.")
        BulletRow("Provider tests with the library's :testing RecordingContext for discovery, measurement states and owner propagation.")
        BulletRow("Analyzer tests for budgets, exclusion predicates, deduplication and accounting-policy conservation.")
        BulletRow("JDBC integration tests for :room + :sqlite against app/schemas/.../1.json using sqlite-jdbc.")
        BulletRow("An instrumented smoke test launches the app, waits for bootstrap and checks the main surfaces render.")
        Text(
            "Run them with ./gradlew :app:testDebugUnitTest and ./gradlew :app:connectedDebugAndroidTest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModuleMapCard() {
    SectionCard(title = "Module map") {
        KeyValueRow(":core", "Contracts, analysis engine, reports, accounting", mono = true)
        KeyValueRow(":filesystem", "java.nio provider, volume facade, hard-link decomposition", mono = true)
        KeyValueRow(":database-api", "JDBC-agnostic database inspection contracts", mono = true)
        KeyValueRow(":sqlite", "JDBC SQLite inspection (JVM)", mono = true)
        KeyValueRow(":room", "Room schema inspection provider (JVM)", mono = true)
        KeyValueRow(":history", "SnapshotStore, comparison, growth model", mono = true)
        KeyValueRow(":reactive", "ReactiveAnalyzer, refresh policies, digests", mono = true)
        KeyValueRow(":testing", "RecordingContext and test fixtures", mono = true)
        KeyValueRow(":example", "Headless JVM examples", mono = true)
        KeyValueRow(":app", "This showcase (Android)", mono = true)
    }
}

private fun coverageTone(status: String): BadgeTone = when (status) {
    "Runs in app" -> BadgeTone.Positive
    "JVM only" -> BadgeTone.Warning
    "Partial" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}
