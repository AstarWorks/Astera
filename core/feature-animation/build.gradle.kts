// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0265).
plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:feature-gameplay"))
    api(project(":kernel:domain"))
    api(project(":kernel:runtime"))
    implementation(libs.slf4j.api)
    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(libs.paper.api)
    testImplementation(libs.kaml)
}
