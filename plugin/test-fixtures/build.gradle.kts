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
    // ADR-0022 Phase 2: test-fixtures now depends on :plugin:core only.
    // Legacy fakes that needed Weapons / Economy types (FakeCurrencyLedger,
    // FakeWeaponRegistry, Specs.weaponSpec) were removed; each feature plugin
    // (Weapons / Economy) reintroduces its own fakes inside its own
    // test-fixtures source set in Phase 3-4.
    api(project(":plugin:core"))
    // JUnit api is exposed so callers can write @Test directly against fixtures
    api(libs.junit.jupiter.api)
    implementation(libs.kotlinx.coroutines.core)
}
