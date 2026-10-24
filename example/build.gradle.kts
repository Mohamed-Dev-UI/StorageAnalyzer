plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(project(":filesystem"))
    implementation(project(":sqlite"))
    implementation(project(":history"))
    implementation(project(":reactive"))
    implementation(project(":room"))
    implementation(project(":testing"))
    implementation(libs.kotlinx.coroutines.core)
    // Demo fixture creation talks to SQLite through plain JDBC; the same bundled
    // driver that :sqlite ships internally is needed on the runtime classpath.
    runtimeOnly(libs.sqlite.jdbc)
    // examples are not run inside the normal test suite; a tiny smoke test runs below.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.mohamed.storageanalyzer.example.StorageAnalyzerDemo")
}

// Manual benchmark probe: enable with `./gradlew :example:test -PwithBenchmarks=true`.
tasks.withType<Test>().configureEach {
    // NOTE: systemProperty(key, Any) does not resolve Providers; use .get().
    systemProperty("withBenchmarks", providers.gradleProperty("withBenchmarks").orElse("false").get())
}
