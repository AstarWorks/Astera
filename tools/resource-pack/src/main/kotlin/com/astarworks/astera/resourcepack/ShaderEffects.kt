package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.domain.effect.ShaderEffect
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/** Effect composition resolves every patch against pristine vanilla; hooks never depend on effect ordering. */
internal object ShaderEffects {
    private val json = ObjectMapper()
    private fun yaml(): Yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
    private data class Effect(val id: String, val dir: Path, val programs: List<String>, val constants: Map<String, Double>, val tint: Int?)

    fun compose(root: Path, vanilla: VanillaShaders, files: MutableMap<String, ByteArray>) {
        if (!Files.isDirectory(root)) return
        val hooks = yaml().load<Map<String, Map<String, String>>>(Files.readString(root.parent.resolve("hooks.yaml")))
        val claims = linkedMapOf(("light-uv" to "${ShaderEffect.BLOCK_LIGHT * 16}:${ShaderEffect.SKY_LIGHT * 16}") to "vfx-surface")
        val effects = Files.list(root).use { dirs -> dirs.sorted().toList() }.map { dir ->
            val raw = yaml().load<Map<String, Any>>(Files.readString(dir.resolve("effect.yaml")))
            require(raw.keys == setOf("id", "programs", "markers", "constants")) { "$dir: expected id, programs, markers, constants" }
            val id = raw["id"] as String
            require(id == dir.fileName.toString() && Regex("[a-z][a-z0-9-]*").matches(id)) { "$dir: invalid effect id '$id'" }
            val programs = (raw["programs"] as List<*>).map { it as String }
            require(programs.isNotEmpty() && programs.distinct() == programs && programs.all { it in setOf("item", "entity") }) { "$id: invalid core programs" }
            val markers = (raw["markers"] as List<*>).map { it as Map<*, *> }
            require(markers.isNotEmpty()) { "$id: markers are required" }
            var tint: Int? = null
            for (marker in markers) {
                require(marker.keys.toSet() == setOf("channel", "value")) { "$id: invalid marker fields" }
                val channel = marker["channel"] as String
                val rawValue = marker["value"] as String
                val value = if (channel == "light-uv") rawValue.split(':').let { parts ->
                    require(parts.size == 2) { "$id: invalid light marker '$rawValue'" }
                    parts[0].toInt().toString() + ":" + if (parts[1] == "*") "*" else parts[1].toInt().toString()
                } else rawValue.toInt().toString()
                require(channel in setOf("light-uv", "tint-rgb", "vfx-tint")) { "$id: unknown marker channel '$channel'" }
                val claimChannel = if (channel == "vfx-tint") "tint-rgb" else channel
                if (channel == "light-uv") {
                    require(Regex("""([0-9]+):([0-9]+|[*])""").matches(value)) { "$id: invalid light marker '$value'" }
                    val (block, sky) = value.split(':')
                    require(block.toInt() in 0..240 && block.toInt() % 16 == 0 &&
                        (sky == "*" || (sky.toInt() in 0..240 && sky.toInt() % 16 == 0))) { "$id: invalid light UV" }
                    require(claims.keys.none { (c, v) -> c == "light-uv" && v.substringBefore(':') == block &&
                        (v.substringAfter(':') == "*" || sky == "*" || v.substringAfter(':') == sky) }) { "$id: overlapping light marker '$value'" }
                    require(block.toInt() != ShaderEffect.BLOCK_LIGHT * 16 || (sky != "*" && sky.toInt() != ShaderEffect.SKY_LIGHT * 16)) { "$id: light marker overlaps VFX protocol" }
                }
                require(claims.putIfAbsent(claimChannel to value, id) == null) { "$id: marker $channel=$value already claimed by ${claims[claimChannel to value]}" }
                if (channel == "vfx-tint") {
                    require(tint == null && programs == listOf("item")) { "$id: VFX tint needs item-only program" }
                    tint = value.toInt().also { require(it in 0..0xffffff && it != 16711422) { "$id: invalid/reserved tint" } }
                }
            }
            val snippets = Files.list(dir).use { paths -> paths.filter { it.fileName.toString().endsWith(".patch") }.toList() }
            require(snippets.isNotEmpty() && snippets.all { path -> programs.any { program ->
                path.fileName.toString() in setOf("$program.vsh.patch", "$program.fsh.patch")
            } }) { "$id: snippets must target declared core programs" }
            val constants = (raw["constants"] as Map<*, *>).entries.associate { (key, value) ->
                require(key is String && Regex("[A-Z][A-Z0-9_]*").matches(key)) { "$id: invalid constant '$key'" }
                key to (value as Number).toDouble().also { require(it.isFinite()) { "$id: non-finite constant" } }
            }
            Effect(id, dir, programs, constants, tint)
        }
        // A marker ledger is shipped for inspection and integration verification.
        files["assets/astera/shader-effects/markers.json"] = json.writeValueAsBytes(claims.map { (key, id) -> mapOf("effect" to id, "channel" to key.first, "value" to key.second) })
        for (program in effects.flatMap { it.programs }.distinct().sorted()) for (stage in listOf("vsh", "fsh")) {
            val name = "$program.$stage"
            val patches = effects.filter { program in it.programs }.mapNotNull { effect ->
                val path = effect.dir.resolve("$name.patch")
                if (!Files.isRegularFile(path)) null else expand(effect, Files.readString(path)).lineSequence().joinToString("\n") { line ->
                    if (line.startsWith("@@ hook: ")) "@@ " + (hooks[name]?.get(line.removePrefix("@@ hook: "))
                        ?: error("${effect.id}: unknown hook $name/$line")) else {
                        require(!line.startsWith("@@ ")) { "${effect.id}: use a named hook" }
                        line
                    }
                }
            }
            val globals = if (name == "item.fsh" && effects.any { it.tint != null })
                "@@ before: uniform sampler2D Sampler0;\n#ifndef GLINT\n#include <minecraft:globals.glsl>\n#endif\n" else ""
            require(patches.isNotEmpty()) { "$name: no effect hooks" }
            val key = "assets/minecraft/shaders/core/$name"
            check(key !in files) { "$key collides with a pack asset" }
            files[key] = ShaderPatch.apply(name, vanilla.coreShader(name), patches.joinToString("\n") + "\n" + globals).toByteArray()
        }
        val references = references(root.parent.parent)
        for ((id, models) in references) {
            val effect = effects.singleOrNull { it.id == id && it.tint != null }
                ?: error("unknown display shader effect '$id'")
            addVariants(effect, models, files)
        }
    }

    private fun expand(effect: Effect, patch: String): String {
        var result = patch.lineSequence().dropWhile { !it.startsWith("@@ ") }.joinToString("\n").also { require(it.isNotEmpty()) { "${effect.id}: empty snippet" } }.replace("\${MARKER}", effect.tint?.toString() ?: "0")
        effect.tint?.let { tint ->
            result = result.replace("\${MARKER_R}", ((tint shr 16) and 255).toDouble().toString())
                .replace("\${MARKER_G}", ((tint shr 8) and 255).toDouble().toString())
                .replace("\${MARKER_B}", (tint and 255).toDouble().toString())
                .replace("\${BLOCK_UV}", (ShaderEffect.BLOCK_LIGHT * 16).toString())
                .replace("\${SKY_UV}", (ShaderEffect.SKY_LIGHT * 16).toString())
        }
        for ((key, value) in effect.constants) result = result.replace("\${$key}", value.toString())
        require(!Regex("""\$\{[^}]+}""").containsMatchIn(result)) { "${effect.id}: unknown snippet constant" }
        return result
    }

    /** Use the production composer and model resolver, including patch/variant merges and item point tables. */
    private fun references(content: Path): Map<String, Set<String>> {
        val (tables, errors) = SkillEngineRegistries.frameTables(content.resolve("frames"))
        check(errors.isEmpty()) { "shader effect model references: invalid frame tables $errors" }
        val references = linkedMapOf<String, MutableSet<String>>()
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    if ("shader_effect" in value) {
                        val id = value["shader_effect"] as? String ?: error("shader_effect must be a literal id")
                        require(value["op"] == "display" && value["kind"] == "item") { "$id: shader_effect requires an item display" }
                        val model = MeshModels.resolve(value["item"] as? String ?: error("$id: missing mesh item"), value["part"] as? String, tables)
                        require(model is MeshModel.Pack) { "$id: shader_effect requires an Astera pack mesh" }
                        references.getOrPut(id) { sortedSetOf() } += model.key
                    }
                    value.values.forEach(::visit)
                }
                is List<*> -> value.forEach(::visit)
            }
        }
        for (directory in listOf("skills", "vfx")) {
            when (val result = SkillComposer(content.resolve(directory)).composeAll()) {
                is Result.Success -> result.value.values.forEach { visit(it.tree) }
                is Result.Failure -> error("shader effect references: ${result.error}")
            }
        }
        return references
    }

    /** Own copies of cuboid models; changing their tint indices never touches the source model or held item. */
    private fun addVariants(effect: Effect, models: Set<String>, files: MutableMap<String, ByteArray>) {
        fun cloneModel(key: String, visiting: Set<String> = emptySet()): String {
            require(key.startsWith("astera:") && key !in visiting) { "${effect.id}: unsupported/cyclic parent $key" }
            val sourcePath = "assets/astera/models/${key.removePrefix("astera:")}.json"
            val target = ShaderEffect.model(effect.id, key)
            val targetPath = "assets/astera/models/${target.removePrefix("astera:")}.json"
            if (targetPath in files) return target
            val source = files[sourcePath] ?: error("${effect.id}: missing model $key")
            val model = json.readTree(source) as ObjectNode
            for (element in model.path("elements")) for (face in element.path("faces")) (face as ObjectNode).put("tintindex", 0)
            val parent = model.path("parent").asText()
            if (parent.startsWith("astera:")) model.put("parent", cloneModel(parent, visiting + key))
            else require(parent.isEmpty() || parent in setOf("minecraft:item/generated", "minecraft:item/handheld", "item/generated", "item/handheld")) { "${effect.id}: unsupported parent $parent" }
            require(!model.path("textures").has("layer1")) { "${effect.id}: multi-layer generated models are unsupported ($key)" }
            files[targetPath] = json.writeValueAsBytes(model)
            return target
        }
        for (sourceItem in models.sorted()) {
            val path = "assets/astera/items/${sourceItem.removePrefix("astera:")}.json"
            val definition = json.readTree(files[path] ?: error("${effect.id}: missing item definition $sourceItem")) as ObjectNode
            val model = definition.path("model")
            require(model.path("type").asText() in setOf("minecraft:model", "model")) { "${effect.id}: unsupported item definition $sourceItem" }
            val copied = cloneModel(model.path("model").asText())
            (model as ObjectNode).put("model", copied)
            model.set<com.fasterxml.jackson.databind.JsonNode>("tints", json.valueToTree(listOf(mapOf("type" to "minecraft:constant", "value" to effect.tint))))
            files["assets/astera/items/${ShaderEffect.model(effect.id, sourceItem).removePrefix("astera:")}.json"] = json.writeValueAsBytes(definition)
        }
    }
}
