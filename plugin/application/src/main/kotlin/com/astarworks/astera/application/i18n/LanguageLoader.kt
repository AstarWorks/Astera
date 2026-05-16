package com.astarworks.astera.application.i18n

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * Loads i18n YAML files into a flat key→value map per locale.
 *
 * Filenames map to locale codes: `ja.yaml` → `ja`, `en.yaml` → `en`.
 * Nested YAML keys are joined with `.` so `astera.weapon.example-sword.name`
 * in YAML becomes the lookup key `astera.weapon.example-sword.name`.
 */
object LanguageLoader {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns `Map<locale, Map<flatKey, value>>`. Missing dir = empty map. */
    fun loadFrom(dir: Path): Map<String, Map<String, Any>> {
        if (!Files.isDirectory(dir)) {
            log.warn("Language directory not found: {}", dir)
            return emptyMap()
        }
        val result = mutableMapOf<String, Map<String, Any>>()
        Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && (it.extension == "yaml" || it.extension == "yml") }
                .forEach { file ->
                    try {
                        val locale = file.nameWithoutExtension
                        val tree = Yaml.default.parseToYamlNode(Files.readString(file))
                        result[locale] = flatten("", tree)
                        log.info("Loaded language: {} (from {})", locale, file.fileName)
                    } catch (t: Throwable) {
                        log.error("Failed to load language file {}: {}", file, t.message, t)
                    }
                }
        }
        return result
    }

    private fun flatten(prefix: String, node: YamlNode): Map<String, Any> {
        return when (node) {
            is YamlMap -> buildMap {
                for ((keyNode, valueNode) in node.entries) {
                    val key = keyNode.content
                    val nextPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                    putAll(flatten(nextPrefix, valueNode))
                }
            }
            is YamlScalar -> mapOf(prefix to node.content)
            is YamlList -> mapOf(prefix to node.items.map { (it as? YamlScalar)?.content ?: it.toString() })
            else -> emptyMap()
        }
    }
}
