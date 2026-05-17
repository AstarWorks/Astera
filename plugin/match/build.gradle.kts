/*
 * :plugin:match — Astera-Match plugin (ADR-0022).
 *
 * Match lifecycle: start / phase transitions / end / entry. Soft-depends
 * on Combat (matches can apply damage). Stats subscribes to MatchEnded.
 *
 * Contents (populated in Phase 4 + Stage 3-改):
 *  - MatchId / Match / MatchPhase / Team / TeamId / Score / MatchStateMachine
 *  - IMatchLifecycle inbound port
 *  - IMatchRepository (in-memory) outbound port
 *  - StartMatch / EndMatch / EntryGame / SwitchPhase use cases
 *  - /astera match * commands
 *
 * Produces: astera-match-<version>.jar
 */

plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugin:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":plugin:test-fixtures"))
}

tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveBaseName.set("astera-match")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Wave 3 (R2) / ADR-0020: Astera-Core's classloader owns plugin/core types
    // at runtime (Bukkit `depend: [Astera-Core]`). Excluding it from this
    // shadowJar preserves KClass identity across plugins so ServiceRegistry
    // and IDomainEventBus lookups match registrations from Astera-Core.
    dependencies {
        exclude(project(":plugin:core"))
    }
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
