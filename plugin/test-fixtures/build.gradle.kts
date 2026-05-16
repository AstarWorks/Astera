/*
 * :plugin:test-fixtures — In-memory / fake adapter implementations.
 *
 * Provides production-quality fakes of application ports (e.g. InMemoryBroadcaster,
 * InMemoryPlayerGateway) for fast tests of :plugin:application and
 * :plugin:adapter:minecraft-api. Pure Kotlin; no Minecraft, no DB drivers.
 *
 * This module is consumed via testImplementation by other modules.
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:domain"))
    api(project(":plugin:application"))
    // JUnit api is exposed so callers can write @Test directly against fixtures
    api(libs.junit.jupiter.api)
    implementation(libs.kotlinx.coroutines.core)
}
