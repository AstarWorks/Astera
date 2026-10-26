// Astera-Core remains the single runtime owner of kernel and core feature libraries (ADR-0265).
plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":core:feature-admin"))
    api(project(":core:feature-animation"))
    api(project(":core:feature-effects"))
    api(project(":core:feature-gameplay"))
    api(project(":core:feature-progression"))
    api(project(":core:feature-pve"))
    api(project(":core:feature-social"))
    api(project(":core:feature-tutorial"))
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:paper"))
    api(project(":kernel:runtime"))
    implementation(libs.packetevents.spigot)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(libs.paper.api)
    testImplementation(libs.kaml)
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
    archiveBaseName.set("astera-core")
    archiveClassifier.set("")
    mergeServiceFiles()
    // PacketEvents ships a plugin.yml too; retain only Astera's descriptor, while merging service files.
    filesMatching("plugin.yml") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    relocate("com.github.retrooper.packetevents", "com.astarworks.astera.internal.packetevents")
    relocate("io.github.retrooper.packetevents", "com.astarworks.astera.internal.packetevents.spigot")
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
