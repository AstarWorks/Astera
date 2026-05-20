/*
 * :plugin:core — Astera-Core plugin (ADR-0022).
 *
 * The mandatory base plugin all other Astera plugins depend on. Provides:
 *  - Shared domain types (Result, MessageKey, PlayerId, ItemId, Vec3, ...)
 *  - Common outbound ports (IScheduler, IMessageRenderer, IBroadcaster,
 *    IDomainEventBus, IItemRegistry, IContentParser, IPlayerGateway, ICommandHandler)
 *  - sealed DomainEvent + all variants (cross-plugin event flow)
 *  - ServiceRegistry (cross-plugin port registration)
 *  - Paper adapter scaffolding (PaperServer / PaperScheduler / PaperBroadcaster /
 *    PaperCommandHandler / PaperPlayer / McPlayerGateway / BukkitEventAdapter framework)
 *  - i18n LanguageLoader, ContentLoader, MutableItemRegistry, CommandSpec
 *
 * PHASE 1 OF MULTI-PLUGIN REFACTOR (2026-05-17): skeleton with placeholder
 * AsteraCorePlugin only. Phase 2 migrates the contents above from legacy
 * :plugin:domain / :plugin:application / :plugin:adapter:* modules.
 *
 * Produces: astera-core-<version>.jar
 */

plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.slf4j.api)

    // Multi-format content parsing (ADR-0016) — Core hosts ContentLoader.
    implementation(libs.kaml)
    implementation(libs.ktoml.core)
    implementation(libs.kotlinx.serialization.json)

    // Logging (runtime)
    runtimeOnly(libs.logback.classic)

    // Observability — Micrometer + Prometheus exposition (Wave _ops, ADR pending).
    // HTTP server is JDK-bundled com.sun.net.httpserver (no extra dep).
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    // Distributed tracing — OpenTelemetry (Wave 5u, ADR-0087).
    // api + context: vendor-neutral types used by ITraceContextPropagator + adapter.
    // sdk + logging exporter: default backend exports spans to console (no infra needed).
    // OTLP exporter ships in the jar so operators flip to Jaeger / Zipkin via env without rebuild.
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.context)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.logging)
    implementation(libs.opentelemetry.exporter.otlp)

    testImplementation(project(":plugin:test-fixtures"))
}

// Expand ${version} into plugin.yml at processResources (Bukkit/Paper standard).
tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

// Shadow jar naming: astera-core-<version>.jar
tasks.shadowJar {
    archiveBaseName.set("astera-core")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
