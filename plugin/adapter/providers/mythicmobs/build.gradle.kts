/*
 * :plugin:adapter:providers:mythicmobs — Optional MythicMobs integration.
 *
 * Soft-dep provider (ADR-0009). Falls back to vanilla mob handling when
 * MythicMobs is not present.
 *
 * mythic-dist version is a sentinel in the catalog; replace at Phase 1 build time.
 */

plugins {
    id("astera.kotlin-paper")
}

dependencies {
    api(project(":plugin:adapter:minecraft-api"))

    // compileOnly("io.lumine:Mythic-Dist:${libs.findVersion("mythic-dist").get()}")
}
