/*
 * :tools:bot-runner - AI protocol bots (ADR-0247). NOT SHIPPED as a plugin, NOT part of `check`.
 *
 * Holds the MCProtocolLib `Bot` client (shared with :tools:e2e), the combat AI and the long-running
 * runner service (docker compose service `bot-runner`) that turns `astera:bots:request` Redis messages
 * into bots that queue and play. Run it with: docker compose -f docker/docker-compose.yml up -d bot-runner
 */

plugins {
    id("astera.kotlin-common")
    `java-library`
    application
}

dependencies {
    api(libs.mcprotocollib)
    api(project(":plugin:core"))
    api(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lettuce.core)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.astarworks.astera.bots.BotRunnerMainKt")
}

// Used by docker/Dockerfile.bot-runner to pre-fetch every runtime dependency.
tasks.register("resolveRuntime") {
    doLast { configurations.runtimeClasspath.get().resolve() }
}
