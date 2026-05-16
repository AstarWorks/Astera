/*
 * :plugin:persistence — Astera-Persistence plugin (ADR-0022).
 *
 * Optional Postgres backend. When loaded, registers Postgres-backed
 * implementations of repositories (IMatchRepository, IPlayerStatsRepository,
 * ICurrencyLedger, ...) with HIGHER priority than the in-memory defaults
 * registered by feature plugins, so `services.get<T>()` returns the
 * Postgres impl. Without this plugin, Astera runs entirely in-memory.
 *
 * Contents (populated in Stage 4-改):
 *  - Exposed + HikariCP + Flyway bootstrap
 *  - PlayerTable / MatchTable / StageTable / CurrencyLedgerTable + impls
 *  - V1__init.sql ... migrations
 *  - Configuration via env vars (ASTERA_PG_URL / _USER / _PASSWORD)
 *
 * PHASE 1: empty skeleton. Phase 4 keeps it empty (no Postgres yet).
 * Stage 4-改 populates Postgres impls.
 *
 * Produces: astera-persistence-<version>.jar
 */

plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":plugin:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

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
    archiveBaseName.set("astera-persistence")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
