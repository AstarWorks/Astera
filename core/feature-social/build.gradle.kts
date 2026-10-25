// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0252).
plugins {
    id("astera.kotlin-paper")
}

dependencies {
    api(project(":kernel:domain"))
    api(project(":kernel:mc-api"))
    api(project(":kernel:runtime"))
    implementation(libs.slf4j.api)
}
