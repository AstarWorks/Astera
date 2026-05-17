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
}

dependencies {
    implementation(project(":plugin:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    compileOnly(project(":plugin:stats"))
    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(project(":plugin:stats"))
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
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
