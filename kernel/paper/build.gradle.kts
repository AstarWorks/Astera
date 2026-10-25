// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0252).
plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:runtime"))
    implementation(libs.slf4j.api)
    api(libs.lettuce.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.context)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.logging)
    implementation(libs.opentelemetry.exporter.otlp)
    compileOnly(libs.packetevents.spigot)
}
