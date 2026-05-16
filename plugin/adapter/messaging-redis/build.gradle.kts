/*
 * :plugin:adapter:messaging-redis — Pub/Sub + cache port implementations.
 *
 * Implements IBroadcaster (pub/sub) and ICache via Lettuce.
 *
 * MUST NOT depend on Minecraft / Bukkit. Enforced by Konsist.
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:application"))

    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.testcontainers)
    testImplementation(project(":plugin:test-fixtures"))
}
