// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0265).
plugins {
    id("astera.kotlin-common")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:runtime"))
    implementation(libs.slf4j.api)
}
