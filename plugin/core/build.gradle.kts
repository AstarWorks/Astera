// Astera-Core remains the single runtime owner of kernel and core feature libraries (ADR-0265).
plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":core:feature-admin"))
    api(project(":core:feature-animation"))
    api(project(":core:feature-effects"))
    api(project(":core:feature-gameplay"))
    api(project(":core:feature-progression"))
    api(project(":core:feature-pve"))
    api(project(":core:feature-social"))
    api(project(":core:feature-tutorial"))
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:paper"))
    api(project(":kernel:runtime"))
    compileOnly(libs.packetevents.spigot)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(libs.paper.api)
    testImplementation(libs.kaml)
}

tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveBaseName.set("astera-core")
    archiveClassifier.set("")
    mergeServiceFiles()
    // ADR-0301: cel-java drags guava / protobuf / antlr / re2j into the jar. Relocate all of it so it can never clash
    // with Paper's own guava or another plugin's protobuf.
    val shaded = "com.astarworks.astera.shaded"
    relocate("dev.cel", "$shaded.dev.cel")
    relocate("com.google.protobuf", "$shaded.com.google.protobuf")
    relocate("com.google.common", "$shaded.com.google.common")
    relocate("com.google.thirdparty", "$shaded.com.google.thirdparty")
    relocate("com.google.re2j", "$shaded.com.google.re2j")
    relocate("com.google.auto", "$shaded.com.google.auto")
    relocate("com.google.errorprone","$shaded.com.google.errorprone")
    relocate("com.google.j2objc", "$shaded.com.google.j2objc")
    relocate("org.antlr", "$shaded.org.antlr")
    relocate("org.jspecify", "$shaded.org.jspecify")
    relocate("org.checkerframework", "$shaded.org.checkerframework")
    relocate("org.threeten", "$shaded.org.threeten")
    relocate("google", "$shaded.google") // .proto resource roots (google/api/expr, google/rpc, google/type) bundled with cel
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
