/*
 * Astera common Kotlin convention plugin.
 *
 * Applied by all Astera modules. Establishes:
 *   - Kotlin JVM compilation targeting JDK 25 toolchain (Paper 26.x baseline)
 *   - JUnit 5 testing
 *   - Detekt (code style only; architecture rules live in :tools:architecture-test)
 *
 * This plugin is INTENTIONALLY agnostic of Minecraft / Bukkit. Modules that need
 * paper-api should also apply `astera.kotlin-paper`. See
 * docs/architecture/dependency-rules.md for the layer matrix.
 */

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("Library alias '$alias' not found in version catalog")
}

// Toolchain: use JDK 25 (required by Paper 26.x at runtime; see ADR-0003).
// Kotlin output: JVM_24 (Kotlin 2.2.20 ceiling). Java output: JVM 25 (Paper 26
// publishes JVM 25 bytecode, so consumers must be JVM 25 for variant resolution).
// This asymmetry is intentional — see gradle.properties for the validation override.
kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_25
    sourceCompatibility = JavaVersion.VERSION_25
}

dependencies {
    "testImplementation"(lib("junit-jupiter"))
    "testImplementation"(lib("assertj-core"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = false
}

// Detekt 1.23.x's bundled Kotlin compiler does not yet understand JDK 25 — it
// crashes on `JavaVersion.parse("25.0.1")`. We DISABLE Detekt tasks for Phase 1
// (Konsist in :tools:architecture-test is the load-bearing architecture gate).
// Re-enable once Detekt 2.x or a 1.24+ JDK 25-aware release is published.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    enabled = false
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    enabled = false
}
