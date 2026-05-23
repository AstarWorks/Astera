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
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // plugin/core is on the runtime classloader via Bukkit `depend: [Astera-Core]`.
    // Use compileOnly so persistence's shadowJar doesn't bundle a duplicate copy
    // of core types (which would break KClass identity for ServiceRegistry overlays).
    compileOnly(project(":plugin:core"))
    // Wave 5f (ADR-0035): Postgres impls for economy ports (ICurrencyIssuanceLog,
    // IMarketplaceRepository, ICreatorRevenueRepository) need economy's
    // domain + port types. compileOnly so we don't bundle a duplicate copy.
    compileOnly(project(":plugin:economy"))
    // Wave 5v (ADR-0088): PostgresMatchReplayRepository implements
    // :plugin:match's IMatchReplayRepository. compileOnly mirrors the
    // economy pattern — the port type lives on Match's classloader at
    // runtime; persistence loads first via `loadbefore` but resolves the
    // port via ServiceRegistry, so a shared class identity is required
    // (which `compileOnly` preserves by not bundling a duplicate).
    compileOnly(project(":plugin:match"))
    // Wave 5refine (ADR-0100): PostgresItemInstanceRepository implements
    // :plugin:items's IItemInstanceRepository. Same compileOnly pattern as
    // marketplace / match — the Items-domain types are on Items' classloader
    // at runtime, persistence resolves the port via ServiceRegistry overlay.
    compileOnly(project(":plugin:items"))
    // Wave 5flex (ADR-0102): PostgresNameplateMilestoneRepository implements
    // :plugin:stats's INameplateMilestoneRepository (and PostgresPowerIndex /
    // PostgresProfileCard implement economy / core ports, already covered).
    // compileOnly mirrors the marketplace / match / items pattern.
    compileOnly(project(":plugin:stats"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Persistence stack (Wave 3 / ADR-0020)
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari.cp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":plugin:core"))
    testImplementation(project(":plugin:economy"))
    testImplementation(project(":plugin:match"))
    testImplementation(project(":plugin:items"))
    testImplementation(project(":plugin:stats"))
    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
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
