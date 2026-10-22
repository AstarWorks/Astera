/*
 * :tools:game-sim — headless game simulator (ADR-0174, WP-S).
 *
 * NON-SHIPPED. Boots core -> items -> combat -> match -> stats -> economy
 * through the very same `*Wiring.install(ctx, ports)` functions the Paper
 * plugins call, with simulator ports (virtual-time scheduler, recording world
 * gateway, tick-stepped projectile service, position-backed target queries,
 * HP-container damage gateway). No Bukkit/Paper on the classpath: paper-api is
 * `compileOnly` in the plugin modules, so it never reaches this runtime.
 *
 * Scenarios are Kotlin DSL JUnit 5 tests; every run exports a JSON trace to
 * build/sim-traces/<scenario>.json (schema: tools/game-sim/README.md).
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:core"))
    api(project(":plugin:items"))
    api(project(":plugin:combat"))
    api(project(":plugin:match"))
    api(project(":plugin:stats"))
    api(project(":plugin:economy"))

    // Cross-plugin in-memory fakes (FakeScheduler, FakeGuiAdapter, FakeBroadcaster, ...).
    api(project(":plugin:test-fixtures"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlinx.collections.immutable) // GuiSpec.slots is a PersistentMap
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Scenarios load the real content/ directory and write traces under build/.
    systemProperty("astera.sim.contentDir", rootProject.file("content").absolutePath)
    systemProperty("astera.sim.traceDir", layout.buildDirectory.dir("sim-traces").get().asFile.absolutePath)
    // Traces are an output of the run; never serve a cached result.
    outputs.upToDateWhen { false }
}

// V-1 (ADR-0225): only the per-skill showcase traces, for :tools:skill-viewer. Not part of `check`
// (the same scenarios also run in `test`).
tasks.register<Test>("showcase") {
    group = "verification"
    description = "Writes build/sim-traces/showcase-<skill>.json for every skill in content/skills"
    val testSet = sourceSets.test.get()
    testClassesDirs = testSet.output.classesDirs
    classpath = testSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*SkillShowcaseScenarioTest") }
}
