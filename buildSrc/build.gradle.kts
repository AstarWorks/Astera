plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin classpath for convention plugins
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

kotlin {
    jvmToolchain(25)
}
