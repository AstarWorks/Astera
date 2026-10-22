/*
 * :tools:lobby-gen — renders the procedural sky-island lobby (ADR-0180) to a
 * top-down + side-elevation PNG, straight from the pure build plan in
 * `:plugin:core` (no Minecraft needed), and prints the NPC stands the plan
 * recommends for the lobby-npcs content files.
 *
 * NON-SHIPPED. Run: ./gradlew :tools:lobby-gen:run   (writes docs/assets/lobby-preview.png)
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(project(":plugin:core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.astarworks.astera.lobbygen.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args = listOf(
        "--content-dir", "$rootDir/content",
        "--out", "$rootDir/docs/assets/lobby-preview.png",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
