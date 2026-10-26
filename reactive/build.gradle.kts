plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
}

kotlin { jvmToolchain(17) }

// The reactive layer adds exactly one dependency to the library stack:
// kotlinx-coroutines (a "boring" dependency every Kotlin consumer already
// has). No engine, framework or platform dependency is introduced here.
dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
