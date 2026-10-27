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
    testImplementation(project(":plugin:chat"))

    // Cross-plugin in-memory fakes (FakeScheduler, FakeGuiAdapter, FakeBroadcaster, ...).
    api(project(":plugin:test-fixtures"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // The bot brain's pure weapon choice (WeaponPolicy) is driven against real wiring by BotTwoWeaponsScenarioTest.
    testImplementation(project(":tools:bot-runner"))
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
    // The long annihilation pillar scenarios produce traces that do not fit the 512 MB default test heap.
    maxHeapSize = "2g"
}
// Warm path of scripts/skill-preview-auto.sh: prints the runtime classpath (Gradle compiles once per code change), so plan dumps
// run as a bare `java -cp` with no Gradle startup per skill.
tasks.register("previewClasspath") {
    val cp = sourceSets.main.get().runtimeClasspath
    dependsOn(cp)
    doLast { println(cp.files.joinToString(":") { it.absolutePath }) }
}
