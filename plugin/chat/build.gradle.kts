plugins {
    id("astera.core-provided")
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugin:core"))
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)
}

tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}

tasks.shadowJar {
    archiveBaseName.set("astera-chat")
    archiveClassifier.set("")
    mergeServiceFiles()
    dependencies {
        exclude(project(":plugin:core"))
        exclude { "${it.moduleGroup}:${it.moduleName}" in project.coreProvidedModules() }
    }
}

tasks.named("build") { dependsOn(tasks.shadowJar) }
