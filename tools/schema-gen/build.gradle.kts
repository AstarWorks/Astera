/*
 * :tools:schema-gen — Generate JSON Schema Draft 7 files from @Serializable Kotlin DTOs.
 */

plugins {
    id("astera.kotlin-common")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugin:core"))
    implementation(project(":plugin:items"))
    implementation(project(":plugin:combat"))
    implementation(project(":plugin:match"))
    implementation(project(":plugin:stats"))
    implementation(project(":plugin:economy"))

    implementation(libs.kotlinx.serialization.json)

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
