/*
 * :plugin:weapons — Astera-Weapons plugin (ADR-0022).
 *
 * The minimal feature plugin that, paired with Astera-Core, enables the
 * "server admin tests a custom weapon" workflow: edit YAML → /astera give →
 * left-click to fire (publishes WeaponFired event). No Combat / Match /
 * Stats / Economy required.
 *
 * Contents (populated in Phase 3):
 *  - Weapon domain (WeaponId, WeaponSpec, Projectile, Gun, DamageProfile)
 *  - IWeaponRegistry, ICooldownTracker
 *  - MutableWeaponRegistry, WeaponLoaderService, InMemoryCooldownTracker
 *  - FireWeaponUseCase, GiveWeaponUseCase
 *  - Paper click-to-fire listener
 *  - /astera give command
 *
 * PHASE 1 OF MULTI-PLUGIN REFACTOR: skeleton with placeholder
 * AsteraWeaponsPlugin only. Phase 3 migrates the contents above.
 *
 * Produces: astera-weapons-<version>.jar
 */

plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugin:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // For @Serializable weapon YAML configs (mirrors legacy :plugin:application)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    // Wave 4.5 (R3.5) / ADR-0027: MatchStartLoadoutApplyListener subscribes to
    // MatchStarted, published by :plugin:match. compileOnly because the Match
    // plugin lives on the runtime classloader via the `softdepend` entry in
    // plugin.yml — Astera-Weapons must boot fine without Astera-Match (e.g.
    // weapon admin / give-flow regression test).
    compileOnly(project(":plugin:match"))

    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(project(":plugin:match"))
}

tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveBaseName.set("astera-weapons")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Wave 3 (R2) / ADR-0020 — see Astera-Core comment in plugin/match.
    dependencies {
        exclude(project(":plugin:core"))
    }
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
