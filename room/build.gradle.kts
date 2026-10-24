plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka.javadoc)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(project(":sqlite"))
    api(project(":reactive"))
    implementation(project(":filesystem"))

    // Used purely as an in-memory JSON DOM parser for Room's schema bundle
    // files; no compiler plugin, no codegen, no @Serializable types exposed.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
