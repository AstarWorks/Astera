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
    // Shared ports and in-memory implementations, without the plugin host.
    api(project(":core:feature-gameplay"))
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:runtime"))
    // JUnit api is exposed so callers can write @Test directly against fixtures
    api(libs.junit.jupiter.api)
    // AssertJ api is exposed so cross-module assertion helpers (e.g. helper/BroadcasterHelper)
    // can ship from this module and downstream test source sets resolve them at compile time.
    api(libs.assertj.core)
    implementation(libs.kotlinx.coroutines.core)
}
