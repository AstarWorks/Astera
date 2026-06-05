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

    // JOML (Wave 5h-fix, ADR-0079 §1): pure-math library that Bukkit's
    // `Transformation` uses for rotation. `core.domain.hologram.*` imports
    // org.joml.Quaternionf directly so the Wave 6b adapter does zero type
    // conversion. Promoted from transitive (via paper-api compileOnly) to
    // explicit api() so the test source set picks it up too.
    api(libs.joml)

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

    // Wave 6b-3 (ADR-0158): Lettuce Redis client for the
    // LettuceRedisBroadcaster cross-server pub/sub IBroadcaster impl.
    // Discord webhook impl uses JDK java.net.http.HttpClient — no dep.
    implementation(libs.lettuce.core)

    // Wave 6c (ADR-0160): Jackson + Kotlin module for the polymorphic
    // BroadcastEventJsonCodec. jackson-databind already on the catalog
    // (tools:content-lint uses it); jackson-module-kotlin lets it round-trip
    // Kotlin data classes (incl. value classes, default args, sealed events)
    // without ad-hoc reflection. Confined to core.adapter.paper.broadcast.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Wave 6c-stub-packetevents (ADR-0170): PacketEvents 2.x softdepend for
    // genuine packet interception scope (anti-cheat MovementValidatorPacketBridge
    // wired to inbound move packets, per-viewer scoreboard / tablist
    // packet sends). `compileOnly` because the runtime jar is supplied by the
    // user-installed PacketEvents plugin — Astera must boot vanilla without
    // it (CLAUDE.md §4 invariant). All imports are confined to
    // `core.adapter.paper.packetevents.*`; the rest of the codebase keeps
    // referring to the vendor-neutral `IPacketInterceptor` / `PacketEnvelope`
    // / `PacketDecision` shapes from `core.domain.packet.*`. GPL-3.0 license
    // does not infect MIT codebase under softdepend + no-shading per the
    // standard interpretation.
    compileOnly(libs.packetevents.spigot)

    testImplementation(project(":plugin:test-fixtures"))

    // Wave 5j Worker γ (HudRenderer): tests assert against Adventure
    // `Component` / `TextColor` / `Key`. Adventure types ship transitively
    // with paper-api, but since paper-api is `compileOnly` (production
    // injects it at runtime via Paper), the test source set needs an
    // explicit pull onto the test compile classpath.
    testImplementation(libs.paper.api)
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
