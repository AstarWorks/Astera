// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0265).
plugins {
    id("astera.kotlin-paper")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:feature-effects"))
    api(project(":core:feature-progression"))
    api(project(":core:feature-social"))
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:paper"))
    api(project(":kernel:runtime"))
    implementation(libs.slf4j.api)
    implementation(libs.kaml)
    testImplementation(project(":plugin:test-fixtures"))
    testImplementation(libs.paper.api)
}
