package com.astarworks.astera.itemart

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.*

/** Strict, tool-only DAG loader and evaluator (ADR-0304). No persistent animation state. */
internal class SpriteGraph private constructor(val nodes: Map<String, Node>, val output: String) {
    data class Node(val type: String, val params: Map<String, Any>, val inputs: Map<String, String>, val imported: BufferedImage? = null)
    data class Ref(val node: String, val port: String)
    data class Spec(val params: Map<String, Param>, val inputs: Map<String, String>, val output: String = "image") {
        val requiredInputs: Set<String> get() = inputs.keys.intersect(setOf("image", "a", "b", "pattern"))
    }
    data class Param(val kind: String, val default: Any, val choices: Set<String> = emptySet())

    /** Straight (unassociated) float RGBA; quantization happens only at the PNG boundary. */
    class Image(val size: Int, val rgba: FloatArray = FloatArray(size * size * 4)) {
        fun at(x: Int, y: Int, c: Int): Float = if (x in 0 until size && y in 0 until size) rgba[(y * size + x) * 4 + c] else 0f
        fun buffered(): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { out ->
            val pixels = IntArray(size * size) { i ->
                fun byte(c: Int) = (rgba[i * 4 + c].coerceIn(0f, 1f) * 255).roundToInt()
                (byte(3) shl 24) or (byte(0) shl 16) or (byte(1) shl 8) or byte(2)
            }
            out.setRGB(0, 0, size, size, pixels, 0, size)
        }
    }

    fun renderer(size: Int, frameCount: Int): Renderer = Renderer(size, frameCount)
    inner class Renderer(val size: Int, val frameCount: Int) {
        private val cache = mutableMapOf<Pair<String, Int>, Any>()
        fun image(frame: Int): Image = value(output, frame) as Image
        fun intermediate(id: String, frame: Int): Any = value(id, frame)
        private fun value(id: String, frame: Int): Any = cache.getOrPut(id to frame) {
            val node = nodes.getValue(id)
            val p = SPECS.getValue(node.type).params.mapValues { (key, spec) ->
                val default = when (key) {
                    "cx", "cy", "ox", "oy" -> size / 2.0
                    "x1" -> if (node.type == "shape.line") size.toDouble() else spec.default
                    "y0", "y1" -> if (node.type == "shape.line") size / 2.0 else spec.default
                    "radius" -> when (node.type) { "shape.circle" -> size * 0.25; "shape.ring" -> size * 0.35; "shape.polygon", "shape.star", "shape.arc" -> size * 0.3; else -> spec.default }
                    "outer_radius" -> size / 2.0
                    "origin", "spawn_origin" -> listOf(size / 2.0, size / 2.0)
                    else -> spec.default
                }
                val raw = node.params[key] ?: default
                val resolved = if (raw is Ref) value(raw.node, frame) else raw
                validate(spec, resolved, "${node.type}.$key at frame $frame")
                resolved
            }
            val inputs = node.inputs.mapValues { (_, source) -> value(source.substringBefore('.'), frame) }
            if (node.type == "shape.field") validateField(p)
            val context = GraphNodes.Context(size, frame, frameCount)
            if (node.imported != null) {
                return@getOrPut GraphNodes.importImage(node.imported, size)
            }
            GraphNodes.compute(node.type, p, inputs, context, { if (frame > 0) value(id, frame - 1) as Image else Image(size) }) { port, past ->
                val source = node.inputs[port] ?: error("${node.type}: missing $port")
                value(source.substringBefore('.'), past) as Image
            }
        }
    }

    companion object {
        private fun number(v: Double = 0.0) = Param("number", v)
        private fun text(v: String) = Param("string", v)
        private fun color(v: String = "#ffffff") = Param("color", v)
        private fun choice(v: String, vararg other: String) = Param("string", v, (other.toList() + v).toSet())
        private fun vec(x: Double, y: Double) = Param("vec2", listOf(x, y))
        private val easing = choice("linear", "ease_in", "ease_out", "ease_in_out", "back_out", "bounce_out", "elastic_out", "sin", "square")
        private val curve = Param("curve", listOf(mapOf("t" to 0.0, "v" to 1.0), mapOf("t" to 1.0, "v" to 0.0)))
        private val gradient = Param("gradient", listOf(mapOf("t" to 0.0, "color" to "#ffffff"), mapOf("t" to 1.0, "color" to "#ffffff00")))
        private val center = mapOf("cx" to number(16.0), "cy" to number(16.0))
        private val origin = mapOf("ox" to number(16.0), "oy" to number(16.0))
        private val shape = center + mapOf("radius" to number(8.0), "color" to color())
        private val image = mapOf("image" to "image")
        val SPECS: Map<String, Spec> = buildMap {
            put("image.import", Spec(mapOf("path" to text("")), emptyMap()))
            put("shape.circle", Spec(shape, emptyMap()))
            put("shape.ring", Spec(shape + mapOf("radius" to number(11.2), "thickness" to number(2.0)), emptyMap()))
            put("shape.line", Spec(mapOf("x0" to number(), "y0" to number(16.0), "x1" to number(32.0), "y1" to number(16.0), "thickness" to number(1.0), "color" to color()), emptyMap()))
            put("shape.polygon", Spec(shape + mapOf("radius" to number(9.6), "sides" to number(5.0), "rotation_deg" to number(), "thickness" to number()), emptyMap()))
            put("shape.star", Spec(shape + mapOf("radius" to number(9.6), "points" to number(5.0), "inner_radius" to number(4.0), "step" to number(1.0), "rotation_deg" to number(), "thickness" to number()), emptyMap()))
            put("shape.arc", Spec(shape + mapOf("radius" to number(9.6), "start_deg" to number(), "end_deg" to number(180.0), "thickness" to number(2.0), "taper" to number()), emptyMap()))
            put("shape.field", Spec(mapOf("variables" to Param("variables", emptyMap<String, String>()), "expression" to Param("expression", "0"), "color" to color(), "smooth" to Param("boolean", true), "ramp" to Param("boolean", true), "dots" to Param("dots", emptyList<Any>())), emptyMap()))
            for (type in listOf("noise.value", "noise.perlin")) put(type, Spec(mapOf("seed" to number(1.0), "scale" to number(4.0), "octaves" to number(3.0)), emptyMap()))
            put("generate.gradient_radial", Spec(center + mapOf("inner_radius" to number(), "outer_radius" to number(16.0), "color_inner" to color(), "color_outer" to color("#ffffff00")), emptyMap()))
            put("generate.scatter", Spec(mapOf("seed" to number(1.0), "count" to number(12.0), "size_min" to number(1.0), "size_max" to number(3.0), "shape" to choice("dot", "square", "star"), "color" to color()), mapOf("mask" to "image")))
            put("generate.fx_scatter", Spec(mapOf("seed" to number(1.0), "count" to number(12.0), "scale_min" to number(0.5), "scale_max" to number(1.0), "rotate_max_deg" to number(180.0), "alpha_jitter" to number(0.0)), mapOf("pattern" to "image")))
            put("xform.translate", Spec(mapOf("dx" to number(), "dy" to number()), image))
            put("xform.rotate", Spec(origin + ("angle_deg" to number()), image))
            put("xform.scale", Spec(origin + mapOf("sx" to number(1.0), "sy" to number(1.0)), image))
            put("xform.mirror", Spec(mapOf("axis" to choice("x", "y", "both")), image))
            put("xform.polar", Spec(mapOf("mode" to choice("to_polar", "to_cartesian"), "origin" to vec(16.0, 16.0), "twist_deg" to number()), image))
            for (type in listOf("xform.repeat_radial", "xform.polar_repeat", "xform.kaleidoscope")) put(type, Spec(mapOf("count" to number(6.0), "origin" to vec(16.0, 16.0), "rotation_deg" to number()), image))
            for (type in listOf("filter.blur", "filter.dilate")) put(type, Spec(mapOf("radius" to number(2.0)), image))
            put("filter.bloom", Spec(mapOf("threshold" to number(0.4), "radius" to number(3.0), "intensity" to number(1.0), "saturation" to number(1.2), "color" to color("#fff7c2")), image))
            put("filter.palette", Spec(mapOf("colors" to number(16.0)), image))
            put("filter.outline", Spec(mapOf("color" to color("#171526"), "thickness" to number(1.0), "mode" to choice("outside", "inside", "replace")), image))
            put("filter.dither", Spec(mapOf("palette_size" to number(4.0), "pattern" to choice("bayer4", "bayer2", "bayer8", "floyd")), image))
            put("filter.directional_warp", Spec(mapOf("angle_deg" to number(), "intensity" to number(4.0)), image + ("map" to "image")))
            put("filter.slope_blur", Spec(mapOf("intensity" to number(6.0), "samples" to number(8.0)), image + ("slope" to "image")))
            put("filter.seamless", Spec(mapOf("blend" to number(0.2)), image))
            put("filter.trail", Spec(mapOf("decay" to number(0.85), "mode" to choice("over", "add", "screen")), image))
            put("filter.feedback", Spec(mapOf("decay" to number(0.85), "mode" to choice("over", "add", "screen"), "feedback_scale" to number(1.0), "feedback_rotate_deg" to number(), "offset_x" to number(), "offset_y" to number()), image))
            for (type in listOf("over", "add", "screen", "multiply", "max")) put("composite.$type", Spec(emptyMap(), mapOf("a" to "image", "b" to "image")))
            put("anim.time", Spec(emptyMap(), emptyMap(), "number"))
            put("values.number", Spec(mapOf("value" to number()), emptyMap(), "number"))
            put("values.vec2", Spec(mapOf("x" to number(), "y" to number()), emptyMap(), "vec2"))
            for (kind in listOf("color", "string", "boolean", "curve", "gradient", "colors", "expression", "variables", "dots")) {
                val param = when (kind) { "color" -> color(); "string" -> text(""); "boolean" -> Param("boolean", false); "curve" -> curve; "gradient" -> gradient; "expression" -> Param("expression", "0"); "variables" -> Param("variables", emptyMap<String, String>()); "dots" -> Param("dots", emptyList<Any>()); else -> Param("colors", listOf("#ffffff")) }
                put("values.$kind", Spec(mapOf("value" to param), emptyMap(), kind))
            }
            put("anim.curve", Spec(mapOf("keyframes" to curve, "mode" to choice("loop", "once", "pingpong")), emptyMap(), "number"))
            put("anim.wiggler", Spec(mapOf("frequency" to number(1.0), "amplitude" to number(1.0), "center" to number(), "seed" to number(1.0)), emptyMap(), "number"))
            put("anim.rotate", Spec(mapOf("origin" to vec(16.0, 16.0), "speed_deg_per_sec" to number(90.0)), image))
            put("anim.scale_pulse", Spec(mapOf("min" to number(0.8), "max" to number(1.2), "period_seconds" to number(1.0), "easing" to easing.copy(default = "sin")), image))
            put("anim.scale_ramp", Spec(mapOf("from" to number(), "to" to number(1.0), "start_frame" to number(), "duration_frames" to number(6.0), "easing" to easing.copy(default = "ease_out"), "after" to choice("hold", "fade", "loop")), image))
            put("anim.stagger", Spec(mapOf("count" to number(3.0), "decay_alpha" to number(0.6), "delay_frames" to number(1.0)), image))
            put("particle.effector", Spec(mapOf("mode" to choice("attract", "repel", "vortex", "wind", "turbulence"), "origin" to vec(16.0, 16.0), "strength" to number(1.0), "radius" to number(16.0), "falloff" to choice("linear", "inverse_square", "none"), "noise_scale" to number(4.0), "evolution_speed" to number(0.5), "octaves" to number(2.0), "noise_seed" to number(1.0)), emptyMap(), "effector"))
            put("particle.emitter", Spec(mapOf("count" to number(24.0), "seed" to number(1.0), "lifetime_frames" to number(12.0), "spawn_mode" to choice("burst", "continuous"), "spawn_origin" to vec(16.0, 16.0), "spawn_radius" to number(), "velocity_min" to number(0.5), "velocity_max" to number(2.0), "velocity_angle_deg" to number(), "velocity_direction" to choice("radial", "random", "angle"), "size_start" to number(2.0), "size_end" to number(), "shape" to choice("dot", "square", "diamond"), "gradient" to gradient, "alpha_over_life" to curve, "frames_x" to number(1.0), "frames_y" to number(1.0), "sprite_spin_deg_per_sec" to number()), mapOf("sprite" to "image", "effector" to "effector")))
            put("output", Spec(emptyMap(), image))
        }

        fun parse(raw: Any?, sourceDir: Path? = null): SpriteGraph {
            val graph = map(raw, "graph")
            keys(graph, setOf("nodes"), "graph")
            val rawNodes = map(graph["nodes"], "graph.nodes")
            require(rawNodes.isNotEmpty()) { "graph.nodes is empty" }
            val nodes = rawNodes.mapValues { (id, value) ->
                require(id.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) { "invalid node id '$id'" }
                val n = map(value, "node $id")
                keys(n, setOf("type", "params", "inputs"), "node $id")
                val type = n["type"] as? String ?: error("node $id needs type")
                val spec = SPECS[type] ?: error("node $id: unknown type '$type'")
                val p = if (n.containsKey("params")) map(n["params"], "$id.params") else emptyMap()
                keys(p, spec.params.keys, "$id.params")
                val params = p.mapValues { (key, v) ->
                    if (v is Map<*, *>) {
                        if (v.keys == setOf("from")) ref(v["from"]) else { validate(spec.params.getValue(key), v, "$id.$key"); v }
                    } else { validate(spec.params.getValue(key), v, "$id.$key"); v ?: error("null param $id.$key") }
                }
                val input = if (n.containsKey("inputs")) map(n["inputs"], "$id.inputs") else emptyMap()
                keys(input, spec.inputs.keys, "$id.inputs")
                val inputs = input.mapValues { (_, v) -> val r = ref(v); "${r.node}.${r.port}" }
                val required = spec.requiredInputs
                require(inputs.keys.containsAll(required)) { "node $id ($type) requires inputs $required" }
                if (type == "shape.field") {
                    if (listOf("variables", "expression", "dots").none { params[it] is Ref }) validateField(params)
                }
                Node(type, params, inputs)
            }
            for ((id, node) in nodes) {
                fun edge(r: Ref, kind: String) {
                    val src = nodes[r.node] ?: error("$id: unknown source node '${r.node}'")
                    val outputKind = SPECS.getValue(src.type).output
                    require(r.port == if (outputKind == "image") "image" else "value") { "$id: unknown port ${r.node}.${r.port}" }
                    require(outputKind == kind) { "$id: ${r.node}.${r.port} emits $outputKind, expected $kind" }
                }
                node.inputs.forEach { (port, v) -> edge(ref(v), SPECS.getValue(node.type).inputs.getValue(port)) }
                node.params.forEach { (key, v) -> if (v is Ref) edge(v, SPECS.getValue(node.type).params.getValue(key).kind) }
            }
            val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
            fun visit(id: String) {
                if (id in done) return
                require(visiting.add(id)) { "graph cycle at $id" }
                val n = nodes.getValue(id)
                (n.inputs.values.map { ref(it).node } + n.params.values.filterIsInstance<Ref>().map { it.node }).forEach(::visit)
                visiting.remove(id); done.add(id)
            }
            nodes.keys.forEach(::visit)
            val outputs = nodes.filterValues { it.type == "output" }.keys
            require(outputs.size == 1) { "graph requires exactly one output node" }
            fun importedPath(v: Any?): String = when (v) {
                is String -> v
                is Ref -> importedPath(nodes.getValue(v.node).params["value"] ?: "")
                else -> error("image.import.path must resolve to a string")
            }
            val loaded = nodes.mapValues { (_, node) ->
                if (node.type != "image.import") node else {
                    val dir = requireNotNull(sourceDir) { "image.import needs a source directory" }.toRealPath()
                    val path = importedPath(node.params["path"])
                    val file = dir.resolve(path).normalize().toRealPath()
                    require(file.startsWith(dir)) { "image.import.path escapes sources directory" }
                    node.copy(imported = requireNotNull(ImageIO.read(file.toFile())) { "unreadable image.import.path $path" })
                }
            }
            return SpriteGraph(loaded, outputs.single())
        }
        private fun validateField(p: Map<String, Any>) {
            val vars = (p["variables"] as? Map<*, *>).orEmpty()
            val symbols = FieldExpression.BUILT_INS.toMutableList()
            for ((name, expr) in vars) {
                require(name is String && name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")) && name !in symbols) { "invalid/repeated field variable $name" }
                FieldExpression.compile(expr.toString(), symbols)
                symbols += name
            }
            FieldExpression.compile(p["expression"] as? String ?: "0", symbols)
            (p["dots"] as? List<*>)?.forEach { dot ->
                val d = map(dot, "dot")
                keys(d, setOf("x", "y", "value", "when"), "dot")
                require(d.keys.containsAll(setOf("x", "y", "value"))) { "dot requires x/y/value" }
                d.values.forEach { FieldExpression.compile(it.toString(), FieldExpression.BUILT_INS) }
            }
        }
        fun map(v: Any?, label: String): Map<String, Any?> {
            require(v is Map<*, *> && v.keys.all { it is String }) { "$label must be a mapping with string keys" }
            return v.entries.associate { it.key as String to it.value }
        }
        private fun keys(v: Map<String, *>, allowed: Set<String>, label: String) {
            require(v.keys.all { it in allowed }) { "$label: unknown keys ${v.keys - allowed}" }
        }
        private fun ref(v: Any?): Ref {
            require(v is String && v.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*\\.(image|value)"))) { "edge must be node.port, got $v" }
            return Ref(v.substringBefore('.'), v.substringAfter('.'))
        }
        fun validate(p: Param, v: Any?, label: String) {
            val valid = when (p.kind) {
                "number" -> v is Number && v.toDouble().isFinite()
                "boolean" -> v is Boolean
                "string", "expression" -> v is String && (p.choices.isEmpty() || v in p.choices)
                "color" -> v is String && v.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
                "vec2" -> v is List<*> && v.size == 2 && v.all { it is Number && it.toDouble().isFinite() }
                "variables" -> v is Map<*, *> && v.keys.all { it is String } && v.values.all { it is String || it is Number }
                "dots" -> v is List<*>
                "colors" -> v is List<*> && v.isNotEmpty() && v.all { it is String && it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) }
                "curve", "gradient" -> {
                    val key = if (p.kind == "curve") "v" else "color"
                    v is List<*> && v.isNotEmpty() && v.all { stop ->
                        stop is Map<*, *> && stop.keys == setOf("t", key) && (stop["t"] as? Number)?.toDouble()?.let { it.isFinite() && it in 0.0..1.0 } == true &&
                            if (key == "v") (stop[key] as? Number)?.toDouble()?.isFinite() == true else (stop[key] as? String)?.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) == true
                    } && v.map { ((it as Map<*, *>)["t"] as Number).toDouble() }.zipWithNext().all { (a, b) -> a < b }
                }
                else -> false
            }
            require(valid) { "$label must be ${p.kind}${if (p.choices.isEmpty()) "" else " ${p.choices}"}, got $v" }
        }
    }
}
