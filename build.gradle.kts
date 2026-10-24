// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
}

// ---------------------------------------------------------------------------
// Publishing (Maven Central via the Central Portal, vanniktech plugin 0.37+)
//
// - Group id / namespace: io.github.mohamed-dev-ui (auto-verified GitHub
//   namespace; see docs/RELEASE.md for the one-time portal setup).
// - Artifact ids are the module names prefixed with `storageanalyzer-`
//   (e.g. :sqlite -> io.github.mohamed-dev-ui:storageanalyzer-sqlite).
// - Release versions are derived from git tags (v1.2.3 -> 1.2.3) passed by CI
//   as -PpublishVersion; local development defaults to 0.1.0.
// ---------------------------------------------------------------------------
val releaseVersion: String = providers.gradleProperty("publishVersion").orElse("0.1.0").get()

allprojects {
    group = "io.github.mohamed-dev-ui"
    version = releaseVersion
}

val moduleDescriptions = mapOf(
    "core" to "Zero-dependency storage analytics core: measurement model with full provenance, resource identity, ownership/accounting policies, aggregation and the StorageAnalyzer facade.",
    "database-api" to "Provider-agnostic relational storage contracts (introspection, data conventions) for building database analytics providers on the Storage Analyzer library.",
    "sqlite" to "Read-only SQLite storage analytics via JDBC: schema-, page- and record-level sizes with honest, auditable provenance.",
    "filesystem" to "Read-only filesystem storage analytics via java.nio: streaming discovery, volume metrics, cluster-accounting and hard-link dedup.",
    "history" to "Append-only snapshot stores, signed-delta comparisons and linear growth projection for the Storage Analyzer library.",
    "reactive" to "Hot/cold analysis orchestration for the Storage Analyzer library: digest-based change detection, refresh policies and coroutine-friendly monitoring.",
    "room" to "Room database integration: schema-bundle-derived entity descriptors, cold one-shot analysis and a change-detecting hot monitor, with zero androidx dependencies.",
    "testing" to "Test utilities for the Storage Analyzer library: recording analysis contexts, temp-dir fixtures and identity builders.",
)

subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        val ext = extensions.getByType<com.vanniktech.maven.publish.MavenPublishBaseExtension>()

        ext.configure(
            com.vanniktech.maven.publish.KotlinJvm(
                javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationJavadoc"),
                sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
            ),
        )

        ext.publishToMavenCentral()

        ext.coordinates(
            groupId = "io.github.mohamed-dev-ui",
            artifactId = "storageanalyzer-" + project.name,
            version = releaseVersion,
        )

        // Signing is required by Maven Central; enable it only when a key is
        // supplied (CI injects env variables), so `publishToMavenLocal` works
        // on developer machines without GPG.
        val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
            System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
        if (hasSigningKey) {
            ext.signAllPublications()
        }

        ext.pom {
            name.set("Storage Analyzer " + project.name.replaceFirstChar { it.uppercase() })
            description.set(moduleDescriptions[project.name] ?: "Storage analytics for the JVM.")
            inceptionYear.set("2026")
            url.set("https://github.com/Mohamed-Dev-UI/StorageAnalyzer")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/Mohamed-Dev-UI/StorageAnalyzer/blob/main/LICENSE")
                }
            }

            developers {
                developer {
                    id.set("Mohamed-Dev-UI")
                    name.set("Mohamed (Mohamed-Dev-UI)")
                    url.set("https://github.com/Mohamed-Dev-UI/")
                }
            }

            scm {
                url.set("https://github.com/Mohamed-Dev-UI/StorageAnalyzer/")
                connection.set("scm:git:git://github.com/Mohamed-Dev-UI/StorageAnalyzer.git")
                developerConnection.set("scm:git:ssh://git@github.com/Mohamed-Dev-UI/StorageAnalyzer.git")
            }
        }
    }
}
