plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(project(":plugin:core")) // Production anchoring / ordinary-text estimate.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.adventure.minimessage)
}

application {
    mainClass.set("com.astarworks.astera.hudpreview.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.register<JavaExec>("render") {
    group = "verification"
    description = "Render a sim trace to 1920x1080 PNGs; -Ptrace=name -Pticks=4,40 -PguiScale=2"
    dependsOn(":tools:resource-pack:buildResourcePack")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    systemProperty("java.awt.headless", "true")
    workingDir = rootDir
    val trace = providers.gradleProperty("trace").orElse("hud-preview-lobby")
    args(
        rootProject.file("tools/game-sim/build/sim-traces/${trace.get()}.json"),
        rootProject.file("build/resource-pack/astera.zip"),
        rootProject.file(providers.gradleProperty("vanillaAssets").orElse("build/hud-preview/vanilla-assets.zip").get()),
        layout.buildDirectory.dir("hud-frames/${trace.get()}").get().asFile,
        providers.gradleProperty("ticks").orElse("last").get(),
        providers.gradleProperty("guiScale").orElse("2").get(),
        providers.gradleProperty("player").orElse("").get(),
        providers.gradleProperty("tab").orElse("false").get(),
    )
}

tasks.test { systemProperty("java.awt.headless", "true") }
