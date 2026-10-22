/*
 * :tools:vfx-preview - offline Java2D renderer for content/vfx choreographies (ADR-0220). NOT shipped.
 *
 *   ./gradlew :tools:vfx-preview:run --args='demo-cross'
 *   ./gradlew :tools:vfx-preview:run --args='--all'
 *       -> build/vfx-preview/<id>/frame_NNN.png + sheet.png
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(project(":plugin:core"))
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.astarworks.astera.vfxpreview.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    systemProperty("astera.contentDir", "$rootDir/content")
}
