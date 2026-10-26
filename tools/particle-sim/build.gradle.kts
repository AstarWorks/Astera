/*
 * :tools:particle-sim - runs the REAL vanilla particle classes (out of the locally installed 26.3 client jar) for the particle
 * emissions of a skill and writes build/skill-preview/<skill>/particles.json. NOT shipped, not part of `check`.
 *
 *   make particle-sim SKILL=bastion-greatsword-s1
 *
 * The client jar is never copied into this repo or its build output: it is read from the local launcher cache at run time
 * (or downloaded from the official launcher manifest into ~/.cache/astera-particle-sim). See README.md.
 */

plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.astarworks.astera.particlesim.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

// Warm path of scripts/skill-preview-auto.sh: prints the runtime classpath so particle-sim runs as a bare `java -cp`.
tasks.register("previewClasspath") {
    val cp = sourceSets.main.get().runtimeClasspath
    dependsOn(cp)
    doLast { println(cp.files.joinToString(":") { it.absolutePath }) }
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
