/*
 * :tools:e2e - black-box end-to-end harness (ADR-0222, QA-1). NOT SHIPPED, NOT part of `check`.
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
    // The Bot client + combat AI live in :tools:bot-runner (ADR-0247) so the runner service can share them.
    testImplementation(project(":tools:bot-runner"))
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
    filter {
        excludeTestsMatching("*.live.*")
        // E2E_ONLY=PersonaWeaponsE2eTest (comma separated simple class names) narrows the run.
        System.getenv("E2E_ONLY")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { includeTestsMatching("*.$it") }
        // E2E_SKIP=PersonaWeaponsE2eTest,WeaponsE2eTest (same format) drops classes from the run; scripts/e2e.sh sets it for the `window` profile.
        System.getenv("E2E_SKIP")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { excludeTestsMatching("*.$it") }
    }
    // Bot sessions are stateful against one server: run strictly serially.
    maxParallelForks = 1
    systemProperty("astera.e2e.report", layout.buildDirectory.file("e2e-report.md").get().asFile.absolutePath)
    systemProperty("astera.e2e.composeFile", rootProject.file("docker/docker-compose.e2e.yml").absolutePath)
    // QA-3: the compose project of this checkout (must match scripts/e2e.sh: astera-e2e-<id>).
    systemProperty("astera.e2e.project", "astera-e2e-$e2eId")
    listOf("host", "port", "rconPort", "rconPassword", "autostart", "serverLog", "via", "proxyHost", "proxyPort", "velocityLog", "redisHost", "redisPort").forEach { k ->
        (project.findProperty("e2e.$k") as String?)?.let { systemProperty("astera.e2e.$k", it) }
    }
    testLogging.showStandardStreams = false
}

// QA-2 (ADR-0221): the same bots, but against the LIVE docker stack (lobby + game behind Velocity) via
// Velocity modern forwarding. `make live-bots SCENARIO=smoke|weapons|match|all` - never part of check.
val live by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the live-stack bot scenarios (needs the astera-dev stack; run through make live-bots)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    val scenario = (project.findProperty("live.scenario") as String?) ?: "all"
    filter {
        if (scenario == "all") includeTestsMatching("*.live.*") else includeTestsMatching("*.live.${scenario.replaceFirstChar { it.uppercase() }}LiveTest")
        isFailOnNoMatchingTests = true
    }
    systemProperty("astera.e2e.report", layout.buildDirectory.file("live-report.md").get().asFile.absolutePath)
    systemProperty("astera.e2e.reportTitle", "Astera live-stack bot report ($scenario)")
    systemProperty("astera.e2e.backendDesc", "astera-dev stack, bots connect directly to the lobby/game backends with Velocity modern forwarding (no Mojang account)")
    listOf("lobbyHost", "gameHost", "rconPassword", "lobbyData", "gameData", "contentDir", "weapons").forEach { k ->
        (project.findProperty("live.$k") as String?)?.let { systemProperty("astera.live.$k", it) }
    }
    testLogging.showStandardStreams = false
}

// Used by docker/Dockerfile.e2e-runner to pre-fetch every runtime dependency so the run is offline.
tasks.register("resolveE2eClasspath") {
    doLast { configurations.testRuntimeClasspath.get().resolve() }
}
