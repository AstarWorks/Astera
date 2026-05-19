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
 *  - Skill{Spec, Id, Trigger, Archetype}, ISkillRegistry, SkillContents
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

    // Wave _items-1 (ADR-0073): :plugin:weapons was deleted; DamageProfile
    // moved to :plugin:core (core.domain.damage), projectile types relocated
    // into combat (combat.domain.projectile). DamageSource.Item carries the
    // ItemId of whatever item caused damage (player held / used). Combat
    // continues to be a soft Bukkit dep for downstream feature plugins.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

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
