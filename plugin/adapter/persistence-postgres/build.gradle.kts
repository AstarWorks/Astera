/*
 * :plugin:adapter:persistence-postgres — Persistence port implementations.
 *
 * Implements IPersistence (and sub-ports like IPlayerRepository, IMatchRepository)
 * via Exposed + PostgreSQL JDBC. Migrations primarily via Exposed `exposed-migration`;
 * Flyway available as fallback.
 *
 * Aligned with AstarManagement (also Exposed-based) — see ADR-0005.
 *
 * MUST NOT depend on Minecraft / Bukkit. Enforced by Konsist.
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:application"))

    implementation(libs.postgresql.jdbc)
    implementation(libs.bundles.exposed)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":plugin:test-fixtures"))
}
