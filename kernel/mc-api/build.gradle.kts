// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0265).
plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(project(":kernel:domain"))
}
