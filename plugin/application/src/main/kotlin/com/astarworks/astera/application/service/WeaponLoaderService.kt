package com.astarworks.astera.application.service

import com.astarworks.astera.application.config.WeaponYamlConfig
import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Reads YAML files under `content/weapons/` and registers parsed weapons.
 *
 * Failure mode: a single bad YAML file logs an error and is skipped; the rest
 * still load. Phase 1 has no hot-reload — call [loadFrom] once at startup.
 */
class WeaponLoaderService(private val registry: MutableWeaponRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Loads every yaml / yml file in [dir]. Missing dir = warn + no-op. */
    fun loadFrom(dir: Path): LoadReport {
        if (!Files.isDirectory(dir)) {
            log.warn("Weapon directory not found: {}", dir)
            return LoadReport(loaded = 0, failed = 0)
        }
        var loaded = 0
        var failed = 0
        Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && (it.extension == "yaml" || it.extension == "yml") }
                .forEach { file ->
                    if (loadFile(file)) loaded++ else failed++
                }
        }
        log.info("Weapon load complete: {} ok, {} failed", loaded, failed)
        return LoadReport(loaded, failed)
    }

    private fun loadFile(file: Path): Boolean = try {
        val text = Files.readString(file)
        val cfg = Yaml.default.decodeFromString(WeaponYamlConfig.serializer(), text)
        val spec = cfg.toSpec()
        registry.register(spec)
        log.info("Loaded weapon: {} (from {})", spec.id, file.name)
        true
    } catch (t: Throwable) {
        log.error("Failed to load weapon from {}: {}", file, t.message, t)
        false
    }

    data class LoadReport(val loaded: Int, val failed: Int)
}
