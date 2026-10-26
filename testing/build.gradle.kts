plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(libs.kotlin.test)
    api(libs.kotlin.test.junit)
    api(libs.junit)
}
