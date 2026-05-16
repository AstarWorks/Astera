/*
 * Astera Paper convention plugin.
 *
 * Extends `astera.kotlin-common` for modules that DIRECTLY touch the Paper / Bukkit
 * server API. Adds paper-api as a compileOnly dependency.
 *
 * IMPORTANT: This plugin MUST NOT be applied to modules in layers below
 * `adapter:minecraft-impl-paper`. The following modules are forbidden from
 * importing Bukkit (enforced by Konsist in :tools:architecture-test):
 *
 *   - :plugin:domain
 *   - :plugin:application
 *   - :plugin:adapter:minecraft-api  (vendor-neutral by definition)
 *   - :plugin:adapter:persistence-postgres
 *   - :plugin:adapter:messaging-redis
 *
 * Eligible modules:
 *   - :plugin:adapter:minecraft-impl-paper
 *   - :plugin:adapter:providers:*       (also need their plugin's compileOnly)
 *   - :plugin:platform-paper-plugin
 *
 * See docs/architecture/mc-adapter-layer.md.
 */

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("astera.kotlin-common")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("paper-api").orElseThrow())
}
