plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(17)
}

// The core is intentionally dependency-free: only the kotlin-stdlib is used.
// This guarantees the core can be consumed by any Kotlin/JVM or Android project
// without pulling storage-engine, framework, or platform dependencies.
dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
