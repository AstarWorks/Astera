/*
 * :plugin:economy — Astera-Economy plugin (ADR-0022).
 *
 * Currency, wallets, shop (Phase 4 future), ender-chest (Phase 4 future).
 * Soft-depends on Stats / Match so reward listeners can fire on mission
 * completion / match end.
 *
 * Contents (populated in Phase 4 + Stage 3-改):
 *  - Currency / Wallet / WalletError
 *  - ICurrencyLedger (in-memory default)
 *  - Deposit / Withdraw / Transfer use cases
 *  - /astera currency * commands
 *  - (Phase 4 future) IShopService, IEnderChestService
 *
 * Produces: astera-economy-<version>.jar
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

    // Wave 4.5 (ADR-0027): Shop YAML DTOs use kotlinx.serialization
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    compileOnly(project(":plugin:stats"))
    compileOnly(project(":plugin:match"))
    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(project(":plugin:stats"))
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
    archiveBaseName.set("astera-economy")
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
