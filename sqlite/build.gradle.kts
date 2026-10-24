plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka.javadoc)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(project(":database-api"))

    // The bundled, native-library-carrying SQLite driver; providers should not
    // hard-depend on this version at build time except for the test harness.
    implementation(libs.sqlite.jdbc)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
