// Bundled into Astera-Core; this library does not declare a Paper plugin (ADR-0252).
plugins {
    id("astera.kotlin-common")
}

dependencies {
    api(libs.kotlinx.collections.immutable)
    api(libs.joml)
}
