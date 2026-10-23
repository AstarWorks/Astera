package com.astarworks.astera.animimport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads native Blockbench project values, not Bedrock-exported (axis-flipped) animation JSON.
 *
 * Blockbench's player-model convention (the template, Bedrock humanoid) puts the front at -Z with the right arm at -X.
 * That is the runtime rig frame (`SkinRigBone`: front +Z, right hand -X, +Y up) reflected through the XY plane, so
 * the emitted values are reflected the same way (see [toRig]): what the animator sees is what plays.
 */
object AnimationImporter {
    private val boneNames = listOf("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")
    private val aliases = boneNames.associateBy { it.replace("_", "") } + mapOf("torso" to "body")
    private val pivots = mapOf(
        "head" to listOf(0.0, 24.0, 0.0), "body" to listOf(0.0, 24.0, 0.0),
        "right_arm" to listOf(-5.0, 22.0, 0.0), "left_arm" to listOf(5.0, 22.0, 0.0),
        "right_leg" to listOf(-1.9, 12.0, 0.0), "left_leg" to listOf(1.9, 12.0, 0.0),
    )
    private val channels = setOf("rotation", "position", "scale")
    private val interpolations = setOf("linear", "step", "catmullrom", "bezier")

    fun importModel(input: Path, output: Path): List<Path> {
        val model = ObjectMapper().readTree(input.toFile())
        val groups = linkedMapOf<String, JsonNode>()
        model.path("groups").forEach { group -> groups[group.path("uuid").asText()] = group }
        fun collect(nodes: JsonNode, depth: Int = 0) {
            nodes.filter { it.isObject }.forEach { node ->
                val uuid = node.path("uuid").asText()
                val group = groups[uuid] ?: node
                require(depth == 0) { "Nested bone '${group.path("name").asText()}': use six sibling groups; parent transforms cannot be flattened." }
                groups[uuid] = group
                collect(node.path("children"), depth + 1)
            }
        }
        collect(model.path("outliner"))
        require(groups.isNotEmpty()) { "No bone groups; start from content/animations/_rig/player-rig.bbmodel." }
        val unknown = groups.values.map { it.path("name").asText() }.filter { bone(it) == null }
        require(unknown.isEmpty()) { "Unknown bones: ${unknown.joinToString()}. Supported: ${boneNames.joinToString()}." }
        val mapped = groups.mapValues { (_, group) -> checkNotNull(bone(group.path("name").asText())) }
        require(mapped.values.toSet().size == mapped.size) { "Multiple groups map to the same bone: ${mapped.values}." }
        groups.values.forEach { group ->
            val target = mapped.getValue(group.path("uuid").asText())
            require(group.path("rotation").all { constant(it, "rest rotation") == 0.0 }) {
                "Bone '${group.path("name").asText()}' has a rotated rest pose; use the template rest pose."
            }
            val origin = group.path("origin")
            if (!origin.isMissingNode) {
                require(origin.isArray && origin.size() == 3 && origin.map { constant(it, "$target pivot") }
                    .zip(pivots.getValue(target)).all { (actual, expected) -> abs(actual - expected) < 1e-6 }) {
                    "$target: pivot must be ${pivots.getValue(target)}; use the template vanilla joint."
                }
            }
        }

        val animations = model.path("animations")
        require(animations.isArray && !animations.isEmpty) { "No animations in $input." }
        // Validate the entire input before overwriting any existing animation file.
        val documents = linkedMapOf<String, Map<String, Any>>()
        animations.forEach { animation ->
            val name = animation.path("name").asText()
            val id = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
            require(id.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "Invalid animation name '$name'." }
            require(id !in documents) { "Animation names collide at id '$id'. Rename them in Blockbench." }
            val seconds = constant(animation.path("length"), "$name length")
            val length = ticks(seconds, "$name length")
            require(length > 0) { "$name: length must round to at least one tick." }
            val loop = when (val mode = animation.path("loop").asText("once")) {
                "loop", "true" -> true
                "once", "false" -> false
                "hold" -> {
                    System.err.println("anim-import: $name: hold exports as loop=false; the runtime blends back to rest.")
                    false
                }
                else -> error("$name: unsupported loop mode '$mode'.")
            }
            val bones = linkedMapOf<String, Any>()
            val animators = animation.path("animators")
            require(animators.isObject) { "$name: animators must be an object." }
            animators.fields().forEach { (uuid, animator) ->
                val target = mapped[uuid] ?: error("$name: unknown animator bone '${animator.path("name").asText(uuid)}' ($uuid).")
                require(!animator.path("rotation_global").asBoolean() && !animator.path("quaternion_interpolation").asBoolean()) {
                    "$name/$target: global rotation and quaternion interpolation are unsupported; use local Euler rotation."
                }
                val keyframes = animator.path("keyframes")
                require(keyframes.isMissingNode || keyframes.isArray) { "$name/$target: keyframes must be an array." }
                val frames = keyframes.toList()
                frames.forEach { frame ->
                    require(frame.path("channel").asText() in channels) { "$name/$target: unsupported channel '${frame.path("channel").asText()}'." }
                }
                val tracks = linkedMapOf<String, Any>()
                channels.forEach { channel ->
                    val context = "$name/$target/$channel"
                    val keys = frames.filter { it.path("channel").asText() == channel }
                        .map { key(it, seconds, channel, context) }.sortedBy { it.time }
                    require(keys.map { it.tick }.toSet().size == keys.size) { "$context: keyframes collide after rounding to 20 tps; move them apart." }
                    if (keys.isNotEmpty()) {
                        tracks[channel] = keys.mapIndexed { index, key ->
                            linkedMapOf<String, Any>("t" to key.tick, "v" to toRig(channel, key.value)).apply {
                                if (index > 0) put("ease", ease(keys[index - 1], key))
                            }
                        }
                    }
                }
                if (tracks.isNotEmpty()) bones[target] = tracks
            }
            documents[id] = linkedMapOf(
                "id" to id, "length_ticks" to length, "loop" to loop,
                "lock_movement" to true, "blend_in_ticks" to 2, "blend_out_ticks" to 3,
                "bones" to bones,
            )
        }
        val yaml = Yaml(DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        })
        Files.createDirectories(output)
        return documents.map { (id, document) ->
            output.resolve("$id.yaml").also { Files.writeString(it, yaml.dump(document)) }
        }
    }

    /**
     * Blockbench frame (front -Z) to runtime rig frame (front +Z): reflection z -> -z. Under a reflection a rotation
     * about X or Y reverses sense and one about Z keeps it, so `Rz*Ry*Rx` stays `Rz*Ry*Rx` with `(x, y, z) -> (-x, -y, z)`;
     * positions negate z; scale is unchanged. `+ 0.0` turns -0.0 into 0.0 for tidy YAML.
     */
    private fun toRig(channel: String, v: List<Double>): List<Double> = when (channel) {
        "rotation" -> listOf(-v[0] + 0.0, -v[1] + 0.0, v[2] + 0.0)
        "position" -> listOf(v[0] + 0.0, v[1] + 0.0, -v[2] + 0.0)
        else -> v
    }

    private fun bone(name: String): String? = aliases[name.lowercase().replace(Regex("[ _-]"), "")]

    private data class Key(
        val time: Double, val tick: Int, val value: List<Double>, val interpolation: String, val source: JsonNode,
    )

    private fun key(source: JsonNode, length: Double, channel: String, context: String): Key {
        val time = constant(source.path("time"), "$context time")
        require(time in 0.0..length) { "$context: time $time is outside 0..$length seconds." }
        val interpolation = source.path("interpolation").asText("linear")
        require(interpolation in interpolations) { "$context: unsupported interpolation '$interpolation'." }
        val points = source.path("data_points")
        require(points.isArray && points.size() == 1) { "$context: exactly one data point per key is supported; remove pre/post discontinuities." }
        val value = listOf("x", "y", "z").map { axis ->
            val node = points[0].path(axis)
            if (node.isMissingNode) (if (channel == "scale") 1.0 else 0.0)
            else constant(node, "$context at $time axis $axis")
        }
        return Key(time, ticks(time, context), value, interpolation, source)
    }

    private fun ticks(seconds: Double, context: String): Int {
        require(seconds >= 0 && seconds * 20 <= Int.MAX_VALUE.toDouble()) { "$context: time out of range." }
        return (seconds * 20).roundToInt()
    }

    private fun constant(node: JsonNode, context: String): Double {
        // Numeric Molang literals (including `return -90;`) are safe without an expression engine.
        val text = node.asText().trim().removePrefix("return ").trim().removeSuffix(";").trim()
        val number = text.toDoubleOrNull()
        require(number != null && number.isFinite()) { "$context: '$text' is not a finite numeric constant. Resolve Molang expressions to numeric keyframes in Blockbench first." }
        return number
    }

    private fun ease(before: Key, after: Key): String = when {
        before.interpolation == "step" -> "step"
        before.interpolation == "catmullrom" || after.interpolation == "catmullrom" -> "catmullrom"
        before.interpolation == "bezier" || after.interpolation == "bezier" -> closestBezierEase(before, after)
        else -> "linear"
    }

    private fun closestBezierEase(before: Key, after: Key): String {
        val duration = after.time - before.time
        val curves = linkedMapOf<String, (Double) -> Double>(
            "linear" to { t -> t },
            "ease_in" to { t -> t * t },
            "ease_out" to { t -> 1 - (1 - t) * (1 - t) },
            "ease_in_out" to { t -> if (t < 0.5) 2 * t * t else 1 - 2 * (1 - t) * (1 - t) },
        )
        fun handle(key: Key, name: String, axis: Int, default: Double): Double =
            key.source.path(name).path(axis).let { if (it.isMissingNode) default else constant(it, name) }
        fun cubic(t: Double, p1: Double, p2: Double): Double =
            3 * (1 - t) * (1 - t) * t * p1 + 3 * (1 - t) * t * t * p2 + t * t * t
        // ponytail: one ease for all axes; arbitrary overshooting/per-axis Beziers need baked tick samples later.
        val scores = curves.mapValues { 0.0 }.toMutableMap()
        for (axis in 0..2) {
            val delta = after.value[axis] - before.value[axis]
            if (abs(delta) < 1e-9) continue
            val x1 = handle(before, "bezier_right_time", axis, 0.1).coerceIn(0.0, duration) / duration
            val x2 = 1 + handle(after, "bezier_left_time", axis, -0.1).coerceIn(-duration, 0.0) / duration
            val y1 = handle(before, "bezier_right_value", axis, 0.0) / delta
            val y2 = 1 + handle(after, "bezier_left_value", axis, 0.0) / delta
            for (sample in 1..19) {
                val time = sample / 20.0
                var low = 0.0
                var high = 1.0
                repeat(32) {
                    val mid = (low + high) / 2
                    if (cubic(mid, x1, x2) < time) low = mid else high = mid
                }
                val value = cubic((low + high) / 2, y1, y2)
                curves.forEach { (name, curve) ->
                    val diff = value - curve(time)
                    scores[name] = scores.getValue(name) + diff * diff
                }
            }
        }
        val result = scores.minBy { it.value }.key
        System.err.println("anim-import: Bezier ${before.time}..${after.time}s approximated with $result.")
        return result
    }
}
