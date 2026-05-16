/*
 * :plugin:adapter:minecraft-api — Vendor-neutral Minecraft concept abstraction.
 *
 * Defines IMcServer / IMcWorld / IMcPlayer / IMcEvent / IMcScheduler and binds
 * application outbound ports (IPlayerGateway, IWorldGateway, ...) to those
 * abstractions.
 *
 * CRITICAL: This module MUST NOT import org.bukkit.* / io.papermc.* / etc.
 * Server-vendor concrete implementations live in sibling modules:
 *   - :plugin:adapter:minecraft-impl-paper
 *   - (future) :plugin:adapter:minecraft-impl-folia, ...
 *
 * Enforced by Konsist in :tools:architecture-test.
 * See docs/architecture/mc-adapter-layer.md, ADR-0002.
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:application"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":plugin:test-fixtures"))
}
