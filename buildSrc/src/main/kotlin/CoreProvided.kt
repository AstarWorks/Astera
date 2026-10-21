import org.gradle.api.Project

/**
 * `group:name` of every module on Core's runtime classpath (resolved through
 * the `coreProvided` configuration from the `astera.core-provided` plugin).
 * Feature `shadowJar`s exclude these so they are loaded once, from Core.
 */
fun Project.coreProvidedModules(): Set<String> =
    configurations.getByName("coreProvided").incoming.resolutionResult.allComponents
        .mapNotNull { it.moduleVersion }
        .map { "${it.group}:${it.name}" }
        .toSet()
