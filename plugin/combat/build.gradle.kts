/*
 * :plugin:combat — Astera-Combat plugin (ADR-0022).
 *
 * Damage / Skill / Status mechanics. Soft-depends on Weapons because
 * DamageSource.Weapon references WeaponId; combat still works against
 * environment / fall / explosion sources without Weapons present.
 *
 * Contents (populated in Phase 4 + Stage 3-改):
 *  - Damage{Attempt, Rule, Source, ResolvedDamage}, DamageProfile, DamageAttribute
 *  - IElementMatrix + YamlElementMatrix
 *  - IDamageGateway + PaperDamageGateway
 *  - Skill{Spec, Id, Trigger, Archetype}, ISkillRegistry, SkillLoaderService
 *  - StatusEffect{Spec, Instance, Id, Kind}, IStatusEffectRegistry, IStatusEffectContainer
 *  - ApplyDamage / CastSkill / ApplyStatus / TickCooldown use cases
 *
 * PHASE 1: skeleton. Phase 4 moves domain + ports. Stage 3-改 adds use cases.
 *
 * Produces: astera-combat-<version>.jar
 */

plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugin:core"))

    // Combat references WeaponId / DamageProfile from :plugin:weapons via
    // DamageSource.Weapon (and SkillParams.damage). Weapons is a soft Bukkit
    // dep (Astera-Combat works without Astera-Weapons; environment / fall /
    // explosion sources still fire) but a compile-time dep is required.
    compileOnly(project(":plugin:weapons"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    // Tests construct WeaponId / DamageProfile concretely.
    testImplementation(project(":plugin:weapons"))
    testImplementation(project(":plugin:test-fixtures"))
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
    archiveBaseName.set("astera-combat")
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
