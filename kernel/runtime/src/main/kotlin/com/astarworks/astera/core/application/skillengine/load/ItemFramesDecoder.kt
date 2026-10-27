package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.nio.file.Files
import java.nio.file.Path

/** One model part of an item (`parts.<name>`): named points and axes in the item display-local frame (F-3). */
public data class ModelPart(
    val name: String,
    val model: String,
    val display: String,
    val length: Double,
    val points: Map<String, Vec3>,
    val axes: Map<String, Vec3>,
)

/** `kind: ItemFrames` document: the point table of one item (`content/frames/<item>.yaml`). */
public data class ItemFrames(val item: String, val parts: Map<String, ModelPart>) {
    /** The part a frame uses: its `part:` name, or `held` for the whole item. */
    public fun partFor(part: String?): ModelPart? = parts[part ?: HELD]

    public companion object {
        public const val HELD: String = "held"
    }
}

/** Reads point tables and feeds them to [FrameTree.definePoint]. */
public object ItemFramesDecoder {
    private val KEYS = setOf("apiVersion", "kind", "item", "parts")
    private val PART_KEYS = setOf("model", "display", "length", "points", "axes")

    public fun parse(text: String, source: String): Result<ItemFrames, SkillDecodeError> {
        val raw = try {
            val opts = LoaderOptions().apply { isAllowDuplicateKeys = false }
            Yaml(SafeConstructor(opts)).load<Any?>(text)
        } catch (e: YAMLException) {
            return Result.failure(SkillDecodeError.Invalid(source, "", e.message?.lineSequence()?.firstOrNull() ?: "invalid YAML"))
        }
        val map = raw as? Map<*, *>
            ?: return Result.failure(SkillDecodeError.TypeMismatch(source, "", "map", ValueDecoding.describe(raw)))
        return decode(map, source)
    }

    public fun decode(tree: Map<*, *>, source: String): Result<ItemFrames, SkillDecodeError> =
        try {
            Result.success(read(tree, source))
        } catch (e: DecodeAbort) {
            Result.failure(e.error)
        }

    /** Loads every `*.yaml` under [dir] (item -> table). A bad file yields an error entry; others still load. */
    public fun loadDirectory(dir: Path): Pair<Map<String, ItemFrames>, List<SkillDecodeError>> {
        if (!Files.isDirectory(dir)) return emptyMap<String, ItemFrames>() to emptyList()
        val tables = LinkedHashMap<String, ItemFrames>()
        val errors = ArrayList<SkillDecodeError>()
        val files = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".yaml") }.sorted().toList() }
        for (file in files) {
            val name = file.fileName.toString()
            when (val r = parse(Files.readString(file), name)) {
                is Result.Success -> {
                    if (tables.put(r.value.item, r.value) != null) {
                        errors += SkillDecodeError.DuplicateId(name, "item", r.value.item)
                    }
                }
                is Result.Failure -> errors += r.error
            }
        }
        return tables to errors
    }

    /**
     * Defines every point of [part] on [node]. Axes are defined as zero-offset points carrying the axis vector, so
     * `<frame>.long` / `<frame>.edge` resolve; `axis` is an alias of `long`.
     */
    public fun definePoints(tree: FrameTree, node: FrameId, part: ModelPart) {
        for ((name, p) in part.points) tree.definePoint(FramePoint(node, name, p))
        for ((name, a) in part.axes) tree.definePoint(FramePoint(node, name, Vec3.ZERO, a))
        part.axes["long"]?.let { tree.definePoint(FramePoint(node, "axis", Vec3.ZERO, it)) }
    }

    private fun read(tree: Map<*, *>, source: String): ItemFrames {
        tree.keys.map { it.toString() }.firstOrNull { it !in KEYS }?.let {
            throw DecodeAbort(SkillDecodeError.UnknownKey(source, "", it))
        }
        if (tree["kind"] != "ItemFrames") ValueDecoding.mismatch(source, "kind", "ItemFrames", tree["kind"])
        val item = tree["item"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(source, "", "item"))
        val partsRaw = tree["parts"] as? Map<*, *> ?: throw DecodeAbort(SkillDecodeError.MissingField(source, "", "parts"))
        val parts = LinkedHashMap<String, ModelPart>()
        for ((k, v) in partsRaw) {
            val name = k.toString()
            val path = "parts.$name"
            val p = v as? Map<*, *> ?: ValueDecoding.mismatch(source, path, "map", v)
            p.keys.map { it.toString() }.firstOrNull { it !in PART_KEYS }?.let {
                throw DecodeAbort(SkillDecodeError.UnknownKey(source, path, it))
            }
            val model = p["model"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(source, path, "model"))
            val length = (p["length"] as? Number)?.toDouble() ?: 0.0
            parts[name] = ModelPart(
                name = name,
                model = model,
                display = p["display"] as? String ?: "fixed",
                length = length,
                points = vecMap(p["points"], source, "$path.points"),
                axes = vecMap(p["axes"], source, "$path.axes"),
            )
        }
        return ItemFrames(item, parts)
    }

    private fun vecMap(raw: Any?, source: String, path: String): Map<String, Vec3> {
        if (raw == null) return emptyMap()
        val m = raw as? Map<*, *> ?: ValueDecoding.mismatch(source, path, "map", raw)
        val out = LinkedHashMap<String, Vec3>()
        for ((k, v) in m) out[k.toString()] = ValueDecoding.literalVec3(v, source, "$path.$k")
        return out
    }
}
