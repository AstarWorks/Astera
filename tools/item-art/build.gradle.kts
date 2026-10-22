/*
 * :tools:item-art - procedural item icons (ADR-0193). NOT shipped.
 *
 *   ./gradlew :tools:item-art:run
 *       -> rewrites content/textures/generated/items/<id>.png, content/models/generated/items/<id>.model.json
 *          and the contact sheet docs/assets/item-art-preview.png
 *   ./gradlew :tools:item-art:checkItemArt   (part of `check`)
 *       -> fails when the committed generated art differs from what the generator draws
 *
 * Deterministic pixel art drawn from code (no generative AI, ADR-0010). Studio-authored assets
 * under content/models/items (Studio) override a generated icon of the same item id in the pack.
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.astarworks.astera.itemart.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args = listOf("write", "$rootDir/content", "$rootDir/docs/assets/item-art-preview.png")
}

val checkItemArt = tasks.register<JavaExec>("checkItemArt") {
    group = "verification"
    description = "Fail when the committed generated item art differs from the generator output."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.itemart.MainKt")
    workingDir = rootDir
    args = listOf("check", "$rootDir/content")
    systemProperty("java.awt.headless", "true")
    inputs.dir("$rootDir/content/items")
    inputs.dir("$rootDir/content/textures/generated")
    inputs.dir("$rootDir/content/models/generated")
}

tasks.named("check") { dependsOn(checkItemArt) }

tasks.test {
    systemProperty("java.awt.headless", "true")
    systemProperty("astera.contentDir", "$rootDir/content")
}
