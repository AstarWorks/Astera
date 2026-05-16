/*
 * :plugin:adapter:providers:oraxen — Optional Oraxen integration.
 *
 * Soft-dep provider (ADR-0009). Falls back to vanilla item/block implementation
 * when Oraxen is not present.
 *
 * oraxen version is a sentinel in the catalog; replace at Phase 1 build time.
 */

plugins {
    id("astera.kotlin-paper")
}

dependencies {
    api(project(":plugin:adapter:minecraft-api"))

    // compileOnly("io.th0rgal:oraxen:${libs.findVersion("oraxen").get()}")
}
