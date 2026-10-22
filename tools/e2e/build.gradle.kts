/*
 * :tools:e2e - black-box end-to-end harness (ADR-0186, QA-1). NOT SHIPPED, NOT part of `check`.
 *
 * MCProtocolLib bots (real Minecraft Java 26.3 protocol clients) join the offline-mode `e2e`
 * backend from docker/docker-compose.e2e.yml and assert on what a client receives: resource pack
 * push, sidebar, action bar, boss bars, command tree, tab completion, chat replies, GUIs.
 *
 *   make e2e                       # boots the backend, runs the suite, writes build/e2e-report.md
 *   ./gradlew :tools:e2e:e2e       # suite only (backend must be up, or -Pe2e.autostart=true)
 *
 * The regular `test` task is disabled so `./gradlew check` never needs docker.
 */

import java.security.MessageDigest

plugins {
    id("astera.kotlin-common")
}

dependencies {
    testImplementation(libs.mcprotocollib)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test { enabled = false }

// QA-3 per-checkout id, same derivation as scripts/e2e.sh: -Pe2e.id, then $ASTERA_E2E_ID, then the
// first 8 hex chars of sha256(canonical checkout path).
val e2eId: String = (findProperty("e2e.id") as String?)
    ?: System.getenv("ASTERA_E2E_ID")
    ?: MessageDigest.getInstance("SHA-256")
        .digest(rootProject.projectDir.canonicalPath.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(8)

val e2e by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the protocol-bot E2E suite against the docker `e2e` backend (needs docker; not part of check)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    // Bot sessions are stateful against one server: run strictly serially.
    maxParallelForks = 1
    systemProperty("astera.e2e.report", layout.buildDirectory.file("e2e-report.md").get().asFile.absolutePath)
    systemProperty("astera.e2e.composeFile", rootProject.file("docker/docker-compose.e2e.yml").absolutePath)
    // QA-3: the compose project of this checkout (must match scripts/e2e.sh: astera-e2e-<id>).
    systemProperty("astera.e2e.project", "astera-e2e-$e2eId")
    listOf("host", "port", "rconPort", "rconPassword", "autostart", "serverLog").forEach { k ->
        (project.findProperty("e2e.$k") as String?)?.let { systemProperty("astera.e2e.$k", it) }
    }
    testLogging.showStandardStreams = false
}

// Used by docker/Dockerfile.e2e-runner to pre-fetch every runtime dependency so the run is offline.
tasks.register("resolveE2eClasspath") {
    doLast { configurations.testRuntimeClasspath.get().resolve() }
}
