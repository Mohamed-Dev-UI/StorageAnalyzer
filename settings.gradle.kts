pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Storage Analyzer"

include(":app") // Android showcase app (Hilt, Room/KSP, Compose)

// Universal Storage Analytics library modules.
include(":core")            // Zero-dependency analytics core
include(":database-api")    // Relational/database provider contracts
include(":sqlite")          // SQLite provider (JDBC)
include(":filesystem")      // JVM filesystem provider (java.nio)
include(":history")         // Optional snapshots, comparisons, forecasting
include(":reactive")        // Hot/cold rerun orchestration (coroutines)
include(":room")            // Room database integration (schema bundle based)
include(":testing")         // Fakes, fixtures, measurement assertions
include(":example")         // Runnable JVM examples
