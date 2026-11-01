package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.StrictYaml
import com.astarworks.astera.core.application.skillengine.load.VanillaRegistryKeys
import com.astarworks.astera.core.domain.skillengine.world.BlockFamily
import java.nio.file.Files
import java.nio.file.Path

/** Content-only targeting registry. Composition freezes a family's validated ids into each break row. */
internal class BlockFamilies(private val content: Path) {
    private val families: Map<String, Set<String>> by lazy {
        val file = content.resolve("engine/block-families.yaml")
        val yaml = StrictYaml.newYaml()
        val raw = yaml.load<Any>(Files.readString(file)) as? Map<*, *> ?: error("$file: expected family mapping")
        val custom = Files.list(content.resolve("blocks")).use { paths ->
            paths.filter { it.toString().endsWith(".yaml") }.toList().map { path ->
                val block = yaml.load<Map<String, Any?>>(Files.readString(path))
                block["id"] as? String ?: error("$path: missing block id")
            }.toSet()
        }
        val assigned = HashSet<String>()
        raw.entries.associate { (key, value) ->
            val name = key as? String ?: error("$file: family name must be a string")
            val entry = value as? Map<*, *> ?: error("$file: $name must be a mapping")
            require(entry.keys == setOf("tags", "blocks")) { "$file: $name requires only tags and blocks" }
            fun strings(field: String): List<String> = (entry[field] as? List<*>)?.map {
                it as? String ?: error("$file: $name.$field requires strings")
            } ?: error("$file: $name.$field requires a list")
            val ids = strings("tags").flatMap { tag ->
                require(tag in setOf("minecraft:mineable/pickaxe", "minecraft:mineable/axe", "minecraft:mineable/shovel")) { "$file: unknown block tag '$tag'" }
                val resource = "/skillengine/${tag.substringAfter('/')}-blocks.txt"
                checkNotNull(javaClass.getResourceAsStream(resource)) { "$file: missing tag snapshot $resource" }
                    .bufferedReader().use { it.readLines().filter(String::isNotBlank) }
            } + strings("blocks")
            for (id in ids) {
                require(id in custom || VanillaRegistryKeys.blockProblem(id) == null) { "$file: unknown block id '$id'" }
                require(assigned.add(id)) { "$file: block '$id' assigned more than once" }
            }
            name to ids.toSet()
        }.also { require(custom.all { it in assigned }) { "$file: custom blocks without a family: ${custom - assigned}" } }
    }

    /** Resolve the family reference after inheritance and patches, before typed op decoding. */
    fun expand(tree: Map<String, Any?>): Map<String, Any?> {
        fun visit(value: Any?): Any? = when (value) {
            is Map<*, *> -> {
                val result = value.entries.associate { it.key.toString() to visit(it.value) }.toMutableMap()
                if ("block_family" in result) {
                    require(result["op"] == "break_area") { "block_family is only valid on break_area" }
                    val family = result.remove("block_family") as? String ?: error("block_family must be a string")
                    require(family != "none" && family in families) { "unknown break family '$family'" }
                    result["block_family"] = BlockFamily(family, families.getValue(family))
                }
                result
            }
            is List<*> -> value.map(::visit)
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        return visit(tree) as Map<String, Any?>
    }
}
