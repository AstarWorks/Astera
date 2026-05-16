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
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":plugin:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Multi-format content parsing (ADR-0016). @Serializable DTOs
    // (WeaponYamlConfig etc.) live in this layer; the domain stays free of
    // serialization annotations.
    //
    // - kaml: YAML 1.2 with native anchor / alias support
    // - ktoml-core: TOML, type-strict and easy for AI agents to emit
    // - kotlinx-serialization-json: JSON, lowest-friction for AI agents
    implementation(libs.kaml)
    implementation(libs.ktoml.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":plugin:test-fixtures"))
}
