/*
 * :plugin:stats — Astera-Stats plugin (ADR-0022).
 *
 * Per-player statistics, leveling, and daily/weekly missions (Norma).
 * Soft-depends on Match / Combat / Weapons because it subscribes to their
 * events (PlayerKilled, DamageDealt, MatchEnded, WeaponFired) via the
 * central IDomainEventBus.
 *
 * Contents (populated in Phase 4 + Stage 3-改):
 *  - PlayerStats / Level / Xp / XpCurve / Norma / MissionProgress / NormaTarget / PeriodKind
 *  - IPlayerStatsRepository / ILevelService / INormaService (in-memory)
 *  - RecordKill / RecordDeath / GrantXp / CompleteMission use cases
 *  - /astera stats * + /astera xp * commands
 *
 * Produces: astera-stats-<version>.jar
 */

plugins {
    id("astera.core-provided")
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

    // Wave _items-1 (ADR-0073): :plugin:weapons renamed to :plugin:items.
    compileOnly(project(":plugin:items"))
    compileOnly(project(":plugin:combat"))
    compileOnly(project(":plugin:match"))

    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(project(":plugin:items"))
    testImplementation(project(":plugin:combat"))
    testImplementation(project(":plugin:match"))
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
    archiveBaseName.set("astera-stats")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Wave 3 (R2) / ADR-0020 — see Astera-Core comment in plugin/match.
    dependencies {
        exclude(project(":plugin:core"))
        exclude { "${it.moduleGroup}:${it.moduleName}" in project.coreProvidedModules() }
    }
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
