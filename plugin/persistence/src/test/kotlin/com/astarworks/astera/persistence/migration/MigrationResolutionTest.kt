package com.astarworks.astera.persistence.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.flywaydb.core.api.migration.JavaMigration
import org.flywaydb.core.internal.scanner.Scanner
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/**
 * WP-P2 regression: Flyway must resolve `db/migration` through the classloader it is
 * handed, not the thread context loader (which on Paper cannot see the plugin jar).
 * No Docker needed — only the resource scan is exercised.
 */
class MigrationResolutionTest {
    @Test
    fun `migrations resolve via an isolated plugin-style classloader with a foreign TCCL`() {
        val classesDir = File(
            Class.forName("com.astarworks.astera.persistence.PersistenceModuleKt")
                .protectionDomain.codeSource.location.toURI(),
        )
        val resourcesRoot = classesDir.parentFile.parentFile.parentFile.resolve("resources/main")
        val onDisk = resourcesRoot.resolve("db/migration")
            .listFiles { f -> f.name.matches(Regex("V\\d+__.*\\.sql")) }!!
        assertThat(onDisk).isNotEmpty()

        val platform = ClassLoader.getPlatformClassLoader()
        val pluginLoader = URLClassLoader(arrayOf(resourcesRoot.toURI().toURL()), platform)
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = platform // like Paper's server loader: blind to the plugin
        try {
            val config = Flyway.configure(pluginLoader).locations("classpath:db/migration").load().configuration
            val found = Scanner(JavaMigration::class.java, config, arrayOf(Location("classpath:db/migration")))
                .getResources("V", ".sql")
            assertThat(found).hasSize(onDisk.size)
        } finally {
            Thread.currentThread().contextClassLoader = previous
            pluginLoader.close()
        }
    }
}
