/*
 * :tools:skill-viewer - headless three.js renderer of `astera-sim-trace/2` skill performances (ADR-0301), the preview feed of
 * skill-compare. NOT shipped and NOT part of `check` (needs bun + a Chromium). Run it through `make skill-preview`.
 *
 *   ./gradlew :tools:skill-viewer:buildViewer   -> dist/ only
 *   ./gradlew :tools:skill-viewer:calibrate     -> display-entity calibration PNGs (build/calibration)
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

tasks.register<Exec>("calibrate") {
    group = "skill viewer"
    description = "Renders calibration displays and asserts their projected bounds (PNGs in build/calibration)"
    dependsOn(install)
    workingDir = viewerDir.asFile
    inputs.dir(rootProject.layout.projectDirectory.dir("content/textures/generated"))
    inputs.dir(rootProject.layout.projectDirectory.dir("content/fonts")).optional()
    commandLine(bun.get(), "run", "calibrate")
}
