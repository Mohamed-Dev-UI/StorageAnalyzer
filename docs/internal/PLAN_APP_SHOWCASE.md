# Plan — `:app` Showcase (Hilt + Room + KSP dogfood app)

> **Internal record.** This is a historical design document, not part of the
> public documentation. Details may be stale; the shipped code in `:app` and
> the public docs are authoritative.

A modern Android showcase in `:app` that demonstrates **every shipped capability** of
the Storage Analyzer library on the app's own sandbox, teaches the recommended
approaches in-app, and doubles as a reference implementation of the library in a
production-style stack (Hilt DI, Room, KSP, Compose Material3, Navigation Compose).

**Status: implemented and verified (2026-10-24), including the instrumented
smoke test. Kept as the design record.**

| Question                        | Answer                                                                 |
|---------------------------------|------------------------------------------------------------------------|
| Data the analyzer points at     | App-private sandbox only (no device-wide scanning; zero permissions)   |
| Room version in the app         | `androidx.room 2.8.5` (classic line — matches the `:room` module's schema-bundle + `room_master_table` semantics; Room 3.0/`room3` is a separate KMP coordinate family) |
| Sold as "modern app" via        | Hilt 2.60.1, KSP 2.2.10-2.0.2, Compose BOM 2026.02.01, Navigation 2.10.2, `hilt-navigation-compose`, ViewModel/StateFlow, coroutines |
| Publishing impact               | None — `:app` does not apply `com.vanniktech.maven.publish` and stays out of the artifact set |

## 1. Goals

1. Cover all library features in one runnable app: filesystem & SQLite analytics,
   record-level field breakdowns, room cold + hot analysis, reactive monitoring
   with digest suppression, snapshots/history/forecast, provenance inspection,
   budgets & cancellation, exclusion semantics, honest `UNAVAILABLE` handling.
2. Teach the *recommended approaches*: each screen names the pattern it shows
   (registration order, fs-first ownership, digest-as-render-key, …) and points
   into the matching scenario of `docs/USAGE.md`.
3. Ban silent zeros at UI level: anything not `MEASURED` renders its reason.
4. Keep the library untouched — the app consumes `:core`, `:filesystem`,
   `:sqlite`, `:history`, `:reactive`, `:room` as normal Gradle modules.

## 2. Non-goals

- Device-wide storage scanning (MediaStore/SAF) — scope: app-private sandbox.
- Instrumented/UI tests (needs an emulator); template `ExampleInstrumentedTest` stays.
- Publishing `:app` or using it on the Maven coordinates page.
- DataStore/preferences integration (a plain seeded workspace + the Room DB is enough surface).

## 3. Fixtures inside the sandbox (all app-private)

| Fixture                                              | Created/maintained by        | Consumed by                                  |
|------------------------------------------------------|------------------------------|----------------------------------------------|
| `filesDir/showcase/workspace` — `logs/app.log`, `images/*.bin`, `notes/*.md` | `WorkspaceSeeder` (pure file ops, unit-tested) | `:filesystem` provider (dashboard, history) |
| `filesDir/databases/showcase.db` — the app's **own** Room database (3 entities, WAL) | Room (Hilt-injected) | `:sqlite` (dashboard), `:room` (Room + Live tabs) |
| `filesDir/showcase/history` — snapshot store dir     | `FileSnapshotStore`          | `:history`                                    |
| `schemas/AppDatabase/1.json` — Room's exported schema bundle, shipped as an APK asset (schemas dir is both the `room.schemaLocation` target and an asset source dir) | KSP/Room at build time      | `SchemaBundleInstaller` copies asset → `cacheDir` → `RoomSchemaBundle.parse` |

Room entities (chosen to make every analyzer surface meaningful):

```kotlin
@Entity(tableName = "journal", indices = [Index("tag")])
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,          // index target → index_MetricTable demo
    val message: String,
    val payload: String?,     // dominant TEXT column → field-breakdown demo
    val createdAt: Long,
)

@Entity(tableName = "artifact", indices = [Index("category")])
data class ArtifactEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,     // DATABASE/MEDIA/LOGS/DOCUMENT mirrors the fs demo tree
    val sizeBytes: Long,
    val attachedAt: Long,
)

@Entity(tableName = "checkpoint")
data class CheckpointRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,        // "seed", "append-50", … — visible growth records
    val createdAt: Long,
)
```

Live-data generators (also on the Live tab): append N journal rows, write a log
chunk, delete the oldest 50 rows, explicit checkpoint. Each is a genuine content
change the monitor's fingerprint must detect — or, with "Refresh (no change)",
a no-op that must **not** re-emit.

## 4. Build wiring

### 4.1 Version catalog additions

```toml
[versions]
ksp = "2.2.10-2.0.2"        # exact Kotlin 2.2.10 pairing; AGP 9 keeps it in sync
hilt = "2.60.1"             # AGP 9 supported since dagger 2.59
room = "2.8.5"
navigationCompose = "2.10.2"
hiltNavigationCompose = "1.3.0"

[libraries]
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx     = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-android-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }  # unused if AGP built-in Kotlin suffices — see 4.3
```

The app also receives library modules:
`implementation(project(":filesystem"))`, `project(":sqlite")`, `project(":history")`,
`project(":reactive")`, `project(":room")` — plain JVM `kotlin("jvm")` modules,
so Android D8 consumes their JDK-17-targeted bytecode (supported by AGP 9).

### 4.2 Room schema export (the showcase keystone)

```kotlin
// app/build.gradle.kts
class RoomSchemaArgProvider(
    @get:Input private val schemaDir: File,
) : CommandLineArgumentProvider {                       // official Android docs pattern
    override fun asArguments() = listOf("room.schemaLocation=${schemaDir.path}")
}

ksp { argProviders.add(RoomSchemaArgProvider(File("$projectDir/schemas"))) }

android { sourceSets { getByName("main") { assets.srcDir("schemas") } } }
```

Effect: `schemas/AppDatabase/1.json` is committed to the repo, exported at build
time by KSP, and packaged as an asset root path `AppDatabase/1.json` — exactly the
bundle prose of [PLAN_ROOM_REACTIVE.md](PLAN_ROOM_REACTIVE.md) §3, now with a
*real* Room app.

### 4.3 KSP/Android plugin interplay

AGP 9.4.1 provides built-in Kotlin (it already applies `org.jetbrains.kotlin.plugin.compose`
fine). Apply `com.google.devtools.ksp` + `com.google.dagger.hilt.android`; do **not**
apply `org.jetbrains.kotlin.android` (built-in Kotlin forbids double-applying).
Plugin block: `android-application`, `kotlin-compose`, `ksp`, `hilt`.

### 4.4 App plumbing

- `gradle.properties`: add `android.useAndroidX=true`, `kotlin.code.style=official`
  (idempotent; template may already carry them).
- Manifest: no new permissions; `@HiltAndroidApp` application subclass added.

## 5. Architecture (packages under `com.mohamed.dev.ui.storageanalyzer`)

```
data/       ShowcaseDatabase.kt   Room DB + entities + DAOs (Flows)
data/       WorkspaceSeeder.kt    pure JVM file ops (unit-testable)
data/       SchemaBundleInstaller.kt  asset → cacheDir copy for RoomSchemaBundle.parse
data/       ShowcaseRepository.kt seeder + DAO writes + snapshot capture orchestration
analyzer/   AnalyzerFacade.kt     owns StorageAnalyzer / RoomDatabaseAnalyzer /
                                  RoomStorageMonitor / snapshot store plumbing
analyzer/   UiModels.kt           sealed UI states + mapping (unit-testable)
di/         AppModule.kt          @Module @InstallIn(SingletonComponent)
ui/         MainActivity.kt       Scaffold + bottom nav + NavHost
ui/screens/ DashboardScreen.kt    + DashboardViewModel
ui/screens/ RoomScreen.kt         + RoomViewModel
ui/screens/ LiveScreen.kt         + LiveViewModel
ui/screens/ HistoryScreen.kt      + HistoryViewModel
ui/screens/ HonestyScreen.kt      + HonestyViewModel
ui/screens/ GuideScreen.kt        static field-guide content (no VM)
ui/components/ MetricRow, ProvenanceSheet, DriftItem, SectionCard, StateBanner
```

### 5.1 DI graph

```kotlin
@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun context(@ApplicationContext c: Context) = c

    @Provides @Singleton
    fun schemaBundle(context: Context): RoomSchemaBundle   // parses installed bundle

    @Provides @Singleton
    fun database(context: Context): ShowcaseDatabase       // Room.databaseBuilder

    @Provides @Singleton
    fun storageAnalyzer(context: Context): StorageAnalyzer
    // FilesystemProvider(roots = [filesDir/showcase/workspace]) FIRST
    // + SQLiteStorageProvider(SQLiteDatabaseSpec(filesDir/databases/showcase.db))

    @Provides @Singleton
    fun roomAnalyzer(...): RoomDatabaseAnalyzer            // spec + schema bundle

    @Provides @Singleton
    fun roomMonitor(...): RoomStorageMonitor               // dispatcher = Dispatchers.IO

    @Provides @Singleton
    fun snapshotStore(context: Context): FileSnapshotStore
}
```

Exactly the wiring the docs recommend, expressed as DI: **filesystem provider
registered first == physical identity owner for dedup**; the SQLite spec aliases
to the container; `RoomStorageMonitor` gets `dispatcher = Dispatchers.IO`
explicitly (virtually-testable pattern from `:reactive`).

### 5.2 UI state contract (mirrors library honesty)

```kotlin
sealed interface ShowcaseUiState<out T> {
    data object Loading : ShowcaseUiState<Nothing>
    data class Ready<T>(val value: T) : ShowcaseUiState<T>
    data class Unavailable(val reason: String) : ShowcaseUiState<Nothing>   // rendered with icon + reason
    data class Failed(val throwable: Throwable, val previous: Any?) : ShowcaseUiState<Nothing>
}
```

`Failed` keeps the previous value on screen (as `:reactive`'s `RerunState.Failed`
does) with a dismissible error banner; `Unavailable` never renders a zero.

### 5.3 Threading

All analysis calls run on `Dispatchers.IO` inside a `viewModelScope` or the
repository's injected scope; monitors receive `dispatcher = Dispatchers.IO`
explicitly; `close()` semantics stay library-owned (`close()` is idempotent,
app never closes the concurrent `StorageAnalyzer` twice — guarded in the facade).

## 6. Screen → feature mapping (the coverage index)

| Tab | Library APIs exercised | Scenario in docs/USAGE.md |
|-----|------------------------|---------------------------|
| Dashboard | `StorageAnalyzer.analyzeGlobal()`, metric query sets, `AnalysisOptions.excluded`, budget (`AnalysisBudget`), `unmeasuredResources`, `measurement.missing()`, `provenance`, `recordAnalytics().aggregateFieldBreakdown` | A, B, C, H |
| Room | `RoomSchemaBundle.parse`, `RoomDatabaseAnalyzer.analyzeOnce`, `RoomEntityStorage`/`RoomTotals`, drift codes | K |
| Live | `RoomStorageMonitor.state/results` hot `StateFlow`, `RefreshPolicy`, digest suppression, `RerunState.Failed(previous)`, single-refresh `refreshNow()` | L |
| History | `captureSnapshot`, `FileSnapshotStore`, `SnapshotComparison.between`, `GrowthModelFactory.fit` | E, F |
| Honesty | `AnalysisOptions.excluded` (with alias-drop INFO), unreadable file path, provider with `unsupportedReasons`, `ctx.cancel()` mastering | C, G, D-partial |
| Guide | static pedagogy + snippet cards (all scenarios cross-linked) | — |

Dashboard extras: pull `wallet`-style Field-breakdown card for the `journal`
table (tag TEXT share) through the SQLite provider's record engine; a
"Budget run" demo chip that runs `analyzeGlobal` with `AnalysisBudget(time=250ms)`
and lets the user press **Cancel** — showing honest partials.

## 7. Honest degradation & runtime risks

| Risk | Behavior in app | Docs touchpoint |
|------|-----------------|-----------------|
| xerial `sqlite-jdbc` natives fail to load on a device (the jar ships `Linux-Android` `.so`s but extraction/SELinux rules on target SDK can refuse) | Every engine-backed surface shows `Unavailable(reason)`; fs-backed tabs still work | COMPATIBILITY (Android row) |
| WAL sidecars visible only while a connection is open | Room tab footnote + sidecar rows show measured-vs-absent state | LIMITATIONS (Room P0 section) |
| Snapshot store idempotency across demo restarts | One snapshot per app session (guard by process-local flag) | History tab note |
| Room schema drift after future entity changes | Drift list explains `schema_version`/`missing_table` honestly — a *feature* demo, not a bug | Scenario K drift table |
| Hilt/AGP9 `enableJetifier` incompatibility (known dagger#5099) | gradle.properties keeps jetifier off (pure AndroidX) | — |

## 8. Testing & verification

- **JVM unit tests** (`:app:testDebugUnitTest`, no Robolectric):
  - `WorkspaceSeederTest` — deterministic fixture bytes/layout in a temp dir.
  - `UiModelsTest` — `GlobalReport → DashboardUiModel` mapping keeps `UNAVAILABLE`
    reasons (no silent zeros), formats by `StorageUnits.formatIec`.
  - `SchemaBundleInstallerTest` — copies a fake asset tree, downgrades cleanly.
- **Toolchain verification**: `./gradlew :app:assembleDebug` must pass
  (this exercises KSP for Room+Hilt, built-in Kotlin, D8).
- **Library regression**: `./gradlew build` stays green (115 tests unchanged);
  `:example:run` untouched.
- **Manual**: install on emulator/device; each tab self-verifies via its own
  visible invariants (digest stability, drift=0 banner, projection horizon).

## 9. Execution order

1. Build wiring (catalog + `:app` build + `gradle.properties`) — verify `assembleDebug` with an empty app shell first.
2. Data layer (`ShowcaseDatabase`, seeder, bundle installer, repository).
3. Analyzer facade + DI module.
4. UI shell (nav + theme) then screens in order: Dashboard → Room → Live → History → Honesty → Guide.
5. Unit tests as each data/utility unit lands.
6. Docs sweep (README module map `:app` row, ARCHITECTURE, COMPATIBILITY, LIMITATIONS, FINAL_REPORT).
7. Full-suite rerun; final report updates.

## 10. Acceptance criteria

- `./gradlew :app:assembleDebug` succeeds with zero warnings-as-errors changes.
- Six tabs render real numbers from the app's own sandbox; every non-`MEASURED`
  metric renders its reason instead of a zero.
- Changing content (append/delete) flips the Live tab via digest change;
  no-op refresh does not.
- Room tab shows the real identity hash truthfully (drift empty → green banner).
- Library modules remain dependency-pure and library tests stay green.
