/*
 * :tools:content-lint — JSON Schema validator for content/<type> yaml files.
 *
 * Single-CLI Gradle module (ADR-0067). Wired into the root project's
 * `contentLint` task; run via `./gradlew contentLint` or directly via
 * `./gradlew :tools:content-lint:run`.
 *
 * Wired into the root `check` since WP15 (schema drift must fail the build).
 *
 * Dependencies:
 *   - com.networknt:json-schema-validator — Draft-07 (matches content/schemas)
 *     and Draft 2020-12 support, active maintenance.
 *   - org.yaml:snakeyaml — YAML 1.1 parser producing nested Map/List/scalar.
 *   - jackson-databind — converts SnakeYAML's `Object` tree into `JsonNode` so
 *     the schema validator can walk it.
 *
 * The pure-JVM CLI also uses the real combat/effects content loaders to bake
 * skills, VFX and particle tables and print advisory cost estimates. Paper
 * remains compile-only in those modules and is absent from this CLI runtime.
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":plugin:combat"))
    // SKL-12: builds the pack and checks every reference in it (PackRefLint).
    implementation(project(":tools:resource-pack"))
    implementation(project(":core:feature-gameplay"))
    // Every category the server reloads is decoded here through its production loader (RuntimeDecode.kt).
    implementation(project(":core:feature-pve"))
    implementation(project(":core:feature-admin"))
    implementation(project(":core:feature-tutorial"))
    implementation(project(":core:feature-animation"))
    implementation(project(":plugin:match"))
    implementation(project(":plugin:stats"))
    implementation(project(":plugin:economy"))
    implementation(project(":plugin:chat"))
    implementation(project(":plugin:test-fixtures"))
    implementation(libs.networknt.json.schema.validator)
    implementation(libs.snakeyaml)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.astarworks.astera.contentlint.MainKt")
}

// Default CLI arguments — point the validator at the repo's content/ and
// content/schemas/ directories from the project root. CI / dev callers can
// override with `./gradlew :tools:content-lint:run --args="..."`.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    systemProperty("astera.minecraftVersion", libs.versions.minecraft.get())
    systemProperty("java.awt.headless", "true")
    // Silent-drop checks of SkillStrictLint fail the run unless -Pastera.strictSkills=false (default: on).
    systemProperty("astera.strictSkills", providers.gradleProperty("astera.strictSkills").getOrElse("true"))
    args = listOf(
        "--content-dir", "$rootDir/content",
        "--schemas-dir", "$rootDir/content/schemas",
    )
}
