/*
 * :tools:skill-viewer - static three.js replay viewer + headless frame renderer for :tools:game-sim traces
 * (ADR-0225). NOT shipped and NOT part of `check` (needs bun + a Chromium).
 *
 *   ./gradlew :tools:skill-viewer:render -Pscenario=simple-shoot   -> build/skill-frames/simple-shoot/{sheet.png,anim.gif,...}
 *   ./gradlew :tools:skill-viewer:render                           -> every skill
 *   ./gradlew :tools:skill-viewer:buildViewer                      -> dist/ only (open dist/index.html in a browser)
 */

val viewerDir = layout.projectDirectory
val bun = providers.gradleProperty("bun").orElse("bun")

val install = tasks.register<Exec>("bunInstall") {
    group = "skill viewer"
    workingDir = viewerDir.asFile
    commandLine(bun.get(), "install", "--frozen-lockfile")
    inputs.files("package.json", "bun.lock")
    outputs.dir("node_modules")
}

val buildViewer = tasks.register<Exec>("buildViewer") {
    group = "skill viewer"
    description = "Bundles src/ (three.js pinned in package.json) into dist/"
    dependsOn(install)
    workingDir = viewerDir.asFile
    commandLine(bun.get(), "run", "build")
    inputs.dir("src")
    inputs.file("package.json")
    outputs.dir("dist")
}

tasks.register<Exec>("render") {
    group = "skill viewer"
    description = "Renders PNG frames + contact sheet + GIF per skill: -Pscenario=<skill id> (default: all skills)"
    dependsOn(buildViewer, ":tools:game-sim:showcase")
    workingDir = viewerDir.asFile
    val scenario = providers.gradleProperty("scenario")
    val extra = if (scenario.isPresent) listOf("--scenario", scenario.get()) else listOf("--all")
    commandLine(
        listOf(
            bun.get(), "scripts/render.mjs",
            "--traces", project(":tools:game-sim").layout.buildDirectory.dir("sim-traces").get().asFile.absolutePath,
            "--out", layout.buildDirectory.dir("skill-frames").get().asFile.absolutePath,
        ) + extra,
    )
}

tasks.register<Exec>("calibrate") {
    group = "skill viewer"
    description = "Renders calibration displays and asserts their projected bounds (PNGs in build/calibration)"
    dependsOn(buildViewer)
    workingDir = viewerDir.asFile
    commandLine(bun.get(), "scripts/calibrate.mjs")
}
