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
 * No Paper / Bukkit / kotlinx.serialization dependency: this is a pure-JVM
 * CLI, not a Minecraft plugin, and is therefore outside the Konsist
 * sibling-isolation matrix (ADR-0022).
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(libs.kotlin.stdlib)
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
    args = listOf(
        "--content-dir", "$rootDir/content",
        "--schemas-dir", "$rootDir/content/schemas",
    )
}
