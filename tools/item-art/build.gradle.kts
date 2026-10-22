/*
 * :tools:item-art - procedural item icons (ADR-0193). NOT shipped.
 *
 *   ./gradlew :tools:item-art:run
 *       -> rewrites content/textures/generated/items/<id>.png, content/models/generated/items/<id>.model.json
 *          and the contact sheet docs/assets/item-art-preview.png
 *   ./gradlew :tools:item-art:checkItemArt   (part of `check`)
 *       -> fails when the committed generated art differs from what the generator draws
 *
 * Deterministic: icons are drawn from code, or converted from a committed raster listed in
 * content/textures/sources/items.yaml (ADR-0238; any tool may make the raster, the build never calls one).
 * Custom particle sprites (ADR-0239) come from content/textures/sources/sprites.yaml: code painters, AI sheet rows
 * or hand-drawn strips become content/textures/generated/sprites/<id>.png (frame strip), content/fonts/vfx/<id>.json
 * (bitmap font, frame i = U+E000 + i), content/sprites/<id>.yaml and the contact sheet docs/assets/sprite-preview.png.
 * Studio-authored assets under content/models/items (Studio) override a generated icon of the same item id.
 *
 *   ./gradlew :tools:item-art:run --args="convert <image> <outDir> [colors]"   -> every converter stage as PNG
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
    args = listOf("write", "$rootDir/content", "$rootDir/docs/assets/item-art-preview.png", "$rootDir/docs/assets/item-icons-preview.png")
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
    inputs.dir("$rootDir/content/textures/sources").optional()
    inputs.dir("$rootDir/content/textures/generated")
    inputs.dir("$rootDir/content/models/generated")
    inputs.dir("$rootDir/content/fonts/vfx").optional()
    inputs.dir("$rootDir/content/sprites").optional()
}

tasks.named("check") { dependsOn(checkItemArt) }

tasks.test {
    systemProperty("java.awt.headless", "true")
    systemProperty("astera.contentDir", "$rootDir/content")
}
