/*
 * :plugin:domain — Lv0 Pure domain layer.
 *
 * MUST NOT depend on any framework, platform, or I/O library.
 * Only Kotlin stdlib (implicit) and kotlinx.coroutines are allowed.
 * Coordinates use the domain's own Vec3 / Quat value types; JOML stays in adapter layers.
 *
 * Enforced by Konsist in :tools:architecture-test.
 * See docs/architecture/layers.md.
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
