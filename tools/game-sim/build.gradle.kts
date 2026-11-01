/*
 * :tools:game-sim — headless game simulator (ADR-0174, WP-S).
 *
 * NON-SHIPPED. Boots core -> items -> combat -> match -> stats -> economy
 * through the very same `*Wiring.install(ctx, ports)` functions the Paper
 * plugins call, with simulator ports (virtual-time scheduler, recording world
 * gateway, tick-stepped projectile service, position-backed target queries,
 * HP-container damage gateway). No Bukkit/Paper on the classpath: paper-api is
 * `compileOnly` in the plugin modules, so it never reaches this runtime.
 *
 * Scenarios are Kotlin DSL JUnit 5 tests; every run exports a JSON trace to
 * build/sim-traces/<scenario>.json (schema: tools/game-sim/README.md).
 */

plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":plugin:core"))
    api(project(":plugin:items"))
    api(project(":plugin:combat"))
    api(project(":plugin:match"))
    api(project(":plugin:stats"))
    api(project(":plugin:economy"))
    // playable.metrics.FrameRenderer reuses ArenaRenderer.top for the per-decision PNG (ADR-0316).
    implementation(project(":tools:arena-preview"))
    testImplementation(project(":plugin:chat"))

    // Cross-plugin in-memory fakes (FakeScheduler, FakeGuiAdapter, FakeBroadcaster, ...).
    api(project(":plugin:test-fixtures"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // The real BotBrain runs headless through SimBotHost; AgentPort walks the seat with the bot Navigator (ADR-0316).
    api(project(":tools:bot-runner"))
    testImplementation(libs.kotlinx.collections.immutable) // GuiSpec.slots is a PersistentMap
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.snakeyaml) // GrammarFieldsScenarioTest parses YAML snippets
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Scenarios load the real content/ directory and write traces under build/.
    systemProperty("astera.sim.contentDir", rootProject.file("content").absolutePath)
    systemProperty("astera.sim.traceDir", layout.buildDirectory.dir("sim-traces").get().asFile.absolutePath)
    // Traces are an output of the run; never serve a cached result.
    outputs.upToDateWhen { false }
    // The long annihilation pillar scenarios produce traces that do not fit the 512 MB default test heap.
    maxHeapSize = "2g"
    // `make skill-trace SKILL=<id>` -> SkillTraceExportScenarioTest writes build/sim-traces/v2/<id>.json (ADR-0301 preview feed).
    providers.gradleProperty("skill").orNull?.let { systemProperty("astera.sim.skill", it) }
}

// Regenerates the shipped-skill parity oracle goldens (src/test/resources/skill-oracle) from the current content; review and commit the diff.
tasks.register<Test>("regenSkillOracle") {
    group = "verification"
    description = "Rewrites tools/game-sim/src/test/resources/skill-oracle from the shipped content/skills (ShippedV3ContentScenarioTest in regen mode)."
    val verification = tasks.named<Test>("test").get()
    testClassesDirs = verification.testClassesDirs
    classpath = verification.classpath
    filter { includeTestsMatching("*ShippedV3ContentScenarioTest*") }
    systemProperty("astera.oracle.regen", "true")
}

// Mass bot-vs-bot play (ADR-0316): `./gradlew :tools:game-sim:massPlay -Pruns=20 [-Pseed=<base>] (run i uses base+i; default base = epoch seconds, printed)` -> build/play/summary.md.
tasks.register<JavaExec>("massPlay") {
    group = "verification"
    description = "Runs N headless matches and writes build/play/<run>/metrics.json + build/play/summary.md."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.gamesim.playable.metrics.MassPlayMainKt")
    workingDir = rootDir
    jvmArgs("-Djava.awt.headless=true", "-Xmx2g", "-Dastera.massplay.maxTicks=${providers.gradleProperty("maxTicks").getOrElse("24000")}", "-Dastera.sim.contentDir=${rootProject.file("content").absolutePath}")
    val out = layout.buildDirectory.dir("play").get().asFile.absolutePath
    args(out, providers.gradleProperty("runs").getOrElse("5"), providers.gradleProperty("seed").getOrElse(""))
}

// First-star probe: `./gradlew -q :tools:game-sim:firstStar -Pseeds=1,2 -Psize=3 [-Psecs=120]`.
tasks.register<JavaExec>("firstStar") {
    group = "verification"
    description = "Per team and seed: seconds from match start to each player's first star."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.gamesim.playable.metrics.FirstStarMainKt")
    workingDir = rootDir
    jvmArgs("-Djava.awt.headless=true", "-Xmx2g", "-Dastera.sim.contentDir=${rootProject.file("content").absolutePath}")
    args(providers.gradleProperty("seeds").getOrElse("1"), providers.gradleProperty("size").getOrElse("3"), providers.gradleProperty("secs").getOrElse("120"))
}

// Kill-value probe: `./gradlew -q :tools:game-sim:killValue -Pseeds=1,2,3 -Psizes=3,6,12` (stars per kill vs farming, per N per team).
tasks.register<JavaExec>("killValue") {
    group = "verification"
    description = "Per N: share of stars from kill drops, stars per kill, farm stars per minute and R = per kill / (farm per min x 0.5)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.gamesim.playable.metrics.KillValueMainKt")
    workingDir = rootDir
    jvmArgs("-Djava.awt.headless=true", "-Xmx${providers.gradleProperty("xmx").getOrElse("3g")}", "-Dastera.sim.contentDir=${rootProject.file("content").absolutePath}")
    args(providers.gradleProperty("seeds").getOrElse("1"), providers.gradleProperty("sizes").getOrElse("3,6,12"), providers.gradleProperty("maxTicks").getOrElse("24000"))
}

// Playable sim (ADR-0316): a Claude session plays one seat headless over JSON lines.
//   printf '%s\n' '{"op":"new","mode":"annihilation","me":"red"}' '{"op":"skip","until":"phase=engage"}' '{"op":"end"}' | ./gradlew -q :tools:game-sim:play
//   ./gradlew -q :tools:game-sim:play --args="path/to/commands.jsonl"     (commands from a file instead of stdin)
tasks.register<JavaExec>("play") {
    group = "application"
    description = "JSON-lines agent port: stdin ops (new/act/skip/look/end) -> one observation line each on stdout."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.astarworks.astera.gamesim.playable.agent.PlayMainKt")
    standardInput = System.`in`
    workingDir = rootDir
    jvmArgs("-Xmx2g")
    systemProperty("astera.sim.contentDir", rootProject.file("content").absolutePath)
    systemProperty("astera.sim.traceDir", layout.buildDirectory.dir("play").get().asFile.absolutePath)
}
