/*
 * :tools:resource-pack - builds the Astera resource pack (ADR-0182).
 *
 *   ./gradlew :tools:resource-pack:buildResourcePack
 *       -> build/resource-pack/astera.zip + astera.zip.sha1 (repository root)
 *   ./gradlew :tools:resource-pack:generateHudArt
 *       -> rewrites the committed HUD glyph PNGs / font JSON under content/hud/
 *
 * The pack is assembled from content/{textures,models,sounds,hud}. Wired into
 * `check`: the pack must build, and the committed HUD art must match what the
 * generator produces (ResourcePackTest).
 *
 * Pure-JVM CLI, not a Minecraft plugin; it reads the glyph table from
 * :kernel:domain so the atlas and the runtime renderer cannot drift apart.
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(project(":kernel:domain"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.jackson.databind)
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.astarworks.astera.resourcepack.MainKt")
}

val packDir = rootProject.layout.buildDirectory.dir("resource-pack")

val buildResourcePack = tasks.register<JavaExec>("buildResourcePack") {
    group = "astera"
    description = "Build build/resource-pack/astera.zip and its SHA-1 from content/."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.resourcepack.MainKt")
    workingDir = rootDir
    args = listOf("pack", "$rootDir/content", packDir.get().asFile.absolutePath)
    inputs.dir("$rootDir/content/items")
    inputs.dir("$rootDir/content/textures")
    inputs.dir("$rootDir/content/models")
    inputs.dir("$rootDir/content/sounds")
    inputs.dir("$rootDir/content/hud")
    inputs.dir("$rootDir/content/pack-assets")
    inputs.dir("$rootDir/content/shader-patches")
    outputs.dir(packDir)
}

tasks.register<JavaExec>("generateHudArt") {
    group = "astera"
    description = "Regenerate content/hud/ glyph PNGs and font JSON from the glyph table."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.resourcepack.MainKt")
    workingDir = rootDir
    args = listOf("hud-art", "$rootDir/content/hud")
}

tasks.named("check") { dependsOn(buildResourcePack) }

tasks.withType<JavaExec>().configureEach {
    systemProperty("astera.minecraftVersion", libs.versions.minecraft.get())
    systemProperty("java.awt.headless", "true")
}

tasks.test {
    systemProperty("astera.minecraftVersion", libs.versions.minecraft.get())
    systemProperty("java.awt.headless", "true")
    systemProperty("astera.contentDir", "$rootDir/content")
}
