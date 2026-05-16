/*
 * :plugin:application — Lv1 Use cases + Ports.
 *
 * Defines use cases that orchestrate the domain, plus inbound / outbound port
 * interfaces. MUST NOT depend on Minecraft / Bukkit / Paper. SLF4J API only
 * (logging implementation is bound by :plugin:platform-paper-plugin).
 *
 * Enforced by Konsist in :tools:architecture-test.
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(project(":plugin:test-fixtures"))
}
