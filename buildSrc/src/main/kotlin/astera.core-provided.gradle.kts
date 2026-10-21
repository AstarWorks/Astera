/*
 * Astera feature-plugin shadow helper.
 *
 * Core's shadow jar ships kotlin-stdlib, kotlinx-serialization, kaml,
 * coroutines, … and feature plugins reach them through Paper
 * `depend: [Astera-Core]`. A second copy inside a feature jar splits those
 * types across plugin classloaders (LinkageError at enable), so each
 * feature `shadowJar` excludes every module returned by `coreProvidedModules()` (CoreProvided.kt).
 *
 * Usage in a feature module:
 *
 *     plugins { id("astera.core-provided") }
 *     tasks.shadowJar {
 *         dependencies {
 *             exclude { "${it.moduleGroup}:${it.moduleName}" in project.coreProvidedModules() }
 *         }
 *     }
 */

val coreProvided: Configuration = configurations.create("coreProvided") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    coreProvided(project(":plugin:core"))
}
