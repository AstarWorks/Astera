/*
 * :plugin:adapter:providers:weaponmechanics — Optional WeaponMechanics integration.
 *
 * Soft-dep provider (ADR-0009). Falls back to vanilla implementation when
 * WeaponMechanics is not present at runtime.
 *
 * weaponmechanics version is intentionally pinned to a sentinel ("999.999.999")
 * in the version catalog; Phase 1 implementation will replace it with the actual
 * release version after compatibility check. The module activates only when the
 * sentinel is replaced.
 */

plugins {
    id("astera.kotlin-paper")
}

dependencies {
    api(project(":plugin:adapter:minecraft-api"))

    // WeaponMechanics is a runtime soft-dep on the target server; compileOnly here.
    // compileOnly("com.cjcrafter:weaponmechanics:${libs.findVersion("weaponmechanics").get()}")
}
