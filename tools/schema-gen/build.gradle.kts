/*
 * :tools:schema-gen — Generate JSON Schema Draft 7 files from @Serializable Kotlin DTOs.
 */

plugins {
    id("astera.kotlin-common")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:feature-animation"))
    implementation(project(":core:feature-effects"))
    implementation(project(":core:feature-gameplay"))
    implementation(project(":kernel:runtime"))
    implementation(project(":plugin:items"))
    implementation(project(":plugin:combat"))
    implementation(project(":plugin:match"))
    implementation(project(":plugin:stats"))
    implementation(project(":plugin:economy"))
    implementation(project(":plugin:chat"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.register<JavaExec>("generateSchemas") {
    group = "astera"
    description = "Generate JSON Schema Draft 7 files from @Serializable Kotlin DTOs into content/schemas/."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.schemagen.SchemaGenMainKt")
    args = listOf("${rootDir}/content/schemas")
}

// v3 skill engine schema (ADR-0303): op variants + timing block + rate laws from the OpSpecRegistry. Not part of the
// drift guard yet: it writes the same skill.schema.json as the v2 generator above, which is removed with the v2 purge (W1b-01).
tasks.register<JavaExec>("generateSkillSchema") {
    group = "astera"
    description = "Generate the v3 skill engine JSON Schema (ADR-0303) into content/schemas/skill.schema.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.schemagen.SkillEngineSchemaMain")
    args = listOf("${rootDir}/content/schemas")
}

// Drift guard: regenerate into build/ and fail when content/schemas/ differs, so a DTO change
// without `./gradlew :tools:schema-gen:generateSchemas` cannot land. Part of `check`.
val generateSchemasToBuildDir = tasks.register<JavaExec>("generateSchemasToBuildDir") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.schemagen.SchemaGenMainKt")
    args = listOf(layout.buildDirectory.dir("schemas-check").get().asFile.absolutePath)
}

val checkSchemas = tasks.register("checkSchemas") {
    group = "verification"
    description = "Fail when content/schemas/ is out of date with the @Serializable DTOs."
    dependsOn(generateSchemasToBuildDir)
    val fresh = layout.buildDirectory.dir("schemas-check")
    val committed = rootProject.layout.projectDirectory.dir("content/schemas")
    doLast {
        val stale = fresh.get().asFile.listFiles { f -> f.name.endsWith(".schema.json") }.orEmpty()
            .filter { f ->
                val existing = committed.file(f.name).asFile
                !existing.isFile || existing.readText() != f.readText()
            }
            .map { it.name }
        check(stale.isEmpty()) {
            "content/schemas out of date: $stale. Run ./gradlew :tools:schema-gen:generateSchemas and commit."
        }
    }
}

tasks.named("check") { dependsOn(checkSchemas) }
