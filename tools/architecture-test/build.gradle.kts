/*
 * :tools:architecture-test — Konsist-based architecture enforcement.
 *
 * This is a TEST-ONLY module. Its job is to fail the build if any Astera module
 * violates the dependency direction rules described in docs/architecture/dependency-rules.md.
 *
 * Run as part of `./gradlew check` (see :plugin:platform-paper-plugin and others).
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// `./gradlew check` at the root invokes `check` on every subproject including this
// one, which transitively runs our Konsist tests via the test task. No extra wiring
// needed (and wiring across afterEvaluate boundaries is fragile under Gradle 9+).

// Konsist scans .kt source files across all modules. Gradle's task input-output
// tracking can't detect that automatically here, so the test would be cached UP-TO-DATE
// after the first run even if violating source is added to another module.
// Force re-run on every invocation. Performance is fine — scanning is fast.
tasks.named<Test>("test") {
    outputs.upToDateWhen { false }
}
