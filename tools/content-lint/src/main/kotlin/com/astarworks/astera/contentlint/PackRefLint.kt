package com.astarworks.astera.contentlint

import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.load.MeshModel
import com.astarworks.astera.core.application.skillengine.load.MeshModels
import com.astarworks.astera.resourcepack.PackReferenceCheck
import com.astarworks.astera.resourcepack.ResourcePackBuilder
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * SKL-12: builds the resource pack exactly as dev does and fails on any reference the client would draw as the purple-black
 * missing texture or model. Two halves:
 *
 *  1. pack-internal references ([PackReferenceCheck]): item definitions, models, textures, atlas coverage, fonts, mcmeta,
 *     sounds, particle sprites; vanilla targets are checked against the client jar when one is found
 *     (`ASTERA_VANILLA_CLIENT_JAR`, or the vfx-shot / launcher cache);
 *  2. server-side references into the pack: every item-point-table part model (`content/frames`), every `mesh` op of a
 *     skill (resolved through [MeshModels], the same resolver the Paper gateway uses) and every `plate` sprite font.
 */
internal class PackRefLint(private val contentDir: Path) {

    class Outcome(val problems: List<String>, val vanillaSkipped: Int, val packFiles: Int)

    fun run(): Outcome {
        val pack = ResourcePackBuilder(contentDir).collect()
        val vanilla = PackReferenceCheck.vanillaAssets()
        val internal = PackReferenceCheck.check(pack, vanilla)
        val problems = internal.problems.toMutableList()

        val (tables, frameErrors) = ItemFramesDecoder.loadDirectory(contentDir.resolve("frames"))
        frameErrors.forEach { problems += "content/frames: $it" }
        for ((item, table) in tables) for ((part, p) in table.parts) {
            val key = if (':' in p.model) p.model else "astera:${p.model}"
            if (key.startsWith("astera:") && "assets/astera/items/${key.removePrefix("astera:")}.json" !in pack) {
                problems += "content/frames/$item.yaml: part '$part' model '$key' has no item definition in the pack"
            }
        }

        val skillsDir = contentDir.resolve("skills")
        if (skillsDir.isDirectory()) {
            for (file in skillsDir.walk().filter { it.extension == "yaml" }.sorted()) {
                val rel = "content/skills/" + file.relativeTo(skillsDir).toString()
                val docs = runCatching {
                    Yaml(SafeConstructor(LoaderOptions())).loadAll(file.readText()).toList()
                }.getOrElse { continue } // parse errors are reported by the schema pass
                docs.forEach { walkOps(it, rel, tables, pack, problems) }
            }
        }
        return Outcome(problems.distinct(), internal.vanillaSkipped, pack.size)
    }

    private fun walkOps(
        node: Any?, file: String, tables: Map<String, com.astarworks.astera.core.application.skillengine.load.ItemFrames>,
        pack: Map<String, ByteArray>, problems: MutableList<String>,
    ) {
        when (node) {
            is Map<*, *> -> {
                when (node["op"]) {
                    "mesh" -> {
                        val item = node["item"]?.toString()
                        if (item != null) {
                            val part = node["part"]?.toString()
                            val id = node["id"]
                            when (val model = MeshModels.resolve(item, part, tables)) {
                                is MeshModel.Pack -> {
                                    val key = model.key
                                    if (key.startsWith("astera:") && "assets/astera/items/${key.removePrefix("astera:")}.json" !in pack) {
                                        problems += "$file: mesh '$id' (item $item${part?.let { ", part $it" } ?: ""}) draws '$key', which has no item definition in the pack"
                                    }
                                }
                                is MeshModel.Vanilla, MeshModel.HeldItem -> Unit
                            }
                        }
                    }
                    "plate" -> {
                        val sprite = node["sprite"]?.toString()
                        if (sprite != null && "assets/astera/font/vfx/$sprite.json" !in pack) {
                            problems += "$file: plate '${node["id"]}' sprite '$sprite' has no font assets/astera/font/vfx/$sprite.json"
                        }
                    }
                }
                node.values.forEach { walkOps(it, file, tables, pack, problems) }
            }
            is Iterable<*> -> node.forEach { walkOps(it, file, tables, pack, problems) }
        }
    }
}
