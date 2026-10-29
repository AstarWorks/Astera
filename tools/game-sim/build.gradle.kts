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
    testImplementation(libs.snakeyaml) // GrammarFieldsScenarioTest parses YAML snippets
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
    // `make skill-trace SKILL=<id>` -> SkillTraceExportScenarioTest writes build/sim-traces/v2/<id>.json (ADR-0301 preview feed).
    providers.gradleProperty("skill").orNull?.let { systemProperty("astera.sim.skill", it) }
}

// Regenerates the shipped-skill parity oracle goldens (src/test/resources/skill-oracle) from the current content; review and commit the diff.
tasks.register<Test>("regenSkillOracle") {
    group = "verification"
    description = "Rewrites tools/game-sim/src/test/resources/skill-oracle from the shipped content/skills (ShippedV3ContentScenarioTest in regen mode)."
    val verification = tasks.named<Test>("test").get()
    testClassesDirs = verification.testClassesDirs
    classpath = verification.classpath
    filter { includeTestsMatching("*ShippedV3ContentScenarioTest*") }
    systemProperty("astera.oracle.regen", "true")
}
