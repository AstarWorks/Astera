/*
 * :tools:skill-lint — structural common-sense checks for skill engine v2 skills (ADR-0299, design section 9).
 *
 * NON-SHIPPED CLI. Composes `astera/v2` YAML (SkillComposer), decodes through the SkillDecoder interface and
 * lints the composed tree and the typed SkillDef. Run: `./gradlew :tools:skill-lint:run --args="--root content/skills"`.
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(project(":kernel:domain"))
    implementation(project(":kernel:runtime"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.astarworks.astera.skilllint.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<Test>().configureEach {
    systemProperty("astera.lint.contentDir", rootProject.file("content").absolutePath)
}
