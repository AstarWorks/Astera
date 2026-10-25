plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(project(":plugin:core"))
    implementation(project(":plugin:match"))
    implementation(project(":tools:lobby-gen"))
    implementation(project(":tools:bot-runner"))
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.astarworks.astera.arenapreview.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Xmx2g")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args("$rootDir/content", "$rootDir/build/arena-previews")
}
