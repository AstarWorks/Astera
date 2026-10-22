/*
 * :plugin:items — Astera-Items plugin (ADR-0073, Wave _items-1).
 *
 * The replacement for the legacy `:plugin:weapons` module. Items is no longer
 * weapon-centric: an [com.astarworks.astera.core.domain.item.ItemSpec] is the
 * single inline definition for swords, armour, tools, consumables, or any
 * other player-carriable thing. Skill binding is an inline `skillBindings`
 * list on the spec (one entry per `SkillTrigger`), so the item-side runtime
 * just translates Bukkit interactions into trigger lookups and publishes a
 * `SkillCastRequested` event for Combat to consume.
 *
 * Contents:
 *  - `IItemDelivery` (give an ItemSpec to a player)
 *  - `GiveItemUseCase` (`/astera-items give`)
 *  - `HandleItemTriggerUseCase` (trigger → `SkillCastRequested` event)
 *  - `ItemSkillTriggerListener` (vendor-neutral event sink)
 *  - `PaperItemMaterializer` (ItemSpec → Bukkit ItemStack with PDC, attrs, lore, durability)
 *  - `PaperItemIdResolver` (read `astera:item_id` PDC from held stack)
 *  - `EnchantmentGateListener` (cancel enchant on `astera:enchantable=false`, keep-on-death)
 *
 * Produces: astera-items-<version>.jar
 */

plugins {
    id("astera.core-provided")
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugin:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // For @Serializable item YAML configs (the application/config DTOs).
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(libs.paper.api)
    testImplementation(libs.mockk)
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
    archiveBaseName.set("astera-items")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Wave 3 (R2) / ADR-0020 — see Astera-Core comment in plugin/match.
    dependencies {
        exclude(project(":plugin:core"))
        exclude { "${it.moduleGroup}:${it.moduleName}" in project.coreProvidedModules() }
    }
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
