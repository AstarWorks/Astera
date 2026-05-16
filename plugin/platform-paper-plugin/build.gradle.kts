/*
 * :plugin:platform-paper-plugin — JavaPlugin entrypoint for Paper.
 *
 * The ONLY module that wires the full Koin graph: domain + application +
 * adapter-minecraft-api + adapter-minecraft-impl-paper + persistence + messaging
 * + provider adapters.
 *
 * Produces the runnable plugin jar via Shadow (`./gradlew :plugin:platform-paper-plugin:shadowJar`).
 *
 * For Folia / Velocity / Fabric support, create sibling :plugin:platform-* modules
 * that wire alternative adapter combinations. domain / application / minecraft-api
 * remain unchanged. See ADR-0001 and docs/architecture/mc-adapter-layer.md.
 */

plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.ksp)
    alias(libs.plugins.shadow)
}

dependencies {
    // Domain + application
    implementation(project(":plugin:application"))
    implementation(project(":plugin:domain"))

    // Adapters
    implementation(project(":plugin:adapter:minecraft-api"))
    implementation(project(":plugin:adapter:minecraft-impl-paper"))
    implementation(project(":plugin:adapter:persistence-postgres"))
    implementation(project(":plugin:adapter:messaging-redis"))
    implementation(project(":plugin:adapter:providers:weaponmechanics"))
    implementation(project(":plugin:adapter:providers:oraxen"))
    implementation(project(":plugin:adapter:providers:mythicmobs"))

    // DI: Koin with KSP annotation processor
    implementation(libs.koin.core)
    implementation(libs.koin.annotations)
    ksp(libs.koin.ksp.compiler)

    // Config (YAML)
    implementation(libs.kaml)

    // Logging (runtime)
    runtimeOnly(libs.logback.classic)
}

// Expand version into paper-plugin.yml at processResources (RTM pattern).
tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

// Shadow jar naming: astera-paper-<version>.jar
tasks.shadowJar {
    archiveBaseName.set("astera-paper")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
