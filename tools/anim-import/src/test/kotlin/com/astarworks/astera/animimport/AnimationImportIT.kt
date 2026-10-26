package com.astarworks.astera.animimport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Native shipped rig -> separate CLI process -> actual YAML parser, without runtime/plugin dependencies. */
class AnimationImportIT {
    @TempDir
    lateinit var temp: Path

    private val mapper = ObjectMapper()
    private val rig = Path.of(checkNotNull(System.getProperty("astera.rig")))
    private val output get() = temp.resolve("animations")

    private fun run(input: Path): Pair<Int, String> {
        val log = temp.resolve("cli.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", checkNotNull(System.getProperty("astera.importClasspath")),
            "com.astarworks.astera.animimport.MainKt", input.toString(), output.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Importer timed out")
        }
        return process.exitValue() to Files.readString(log)
    }

    private fun edited(change: (ObjectNode) -> Unit): Path {
        val model = mapper.readTree(rig.toFile()) as ObjectNode
        change(model)
        return temp.resolve("edited.bbmodel").also { mapper.writeValue(it.toFile(), model) }
    }

    private fun frames(model: JsonNode): ArrayNode =
        model.path("animations")[0].path("animators").elements().next().path("keyframes") as ArrayNode

    private fun decode(id: String = "sword-swing"): JsonNode =
        mapper.valueToTree(Yaml().load<Any>(Files.readString(output.resolve("$id.yaml"))))

    @Test
    fun `shipped classic rig sample imports and decodes the shared contract`() {
        val result = run(rig)
        assertThat(result.first).describedAs(result.second).isZero()
        val doc = decode()
        assertThat(doc.path("id").asText()).isEqualTo("sword-swing")
        assertThat(doc.path("length_ticks").asInt()).isEqualTo(12)
        assertThat(doc.path("loop").asBoolean()).isFalse()
        assertThat(doc.path("lock_movement").asBoolean()).isTrue()
        assertThat(doc.path("blend_in_ticks").asInt()).isEqualTo(2)
        assertThat(doc.path("blend_out_ticks").asInt()).isEqualTo(3)
        val arm = doc.path("bones").path("right_arm")
        assertThat(arm.path("rotation").map { it.path("t").asInt() }).containsExactly(0, 4, 12)
        assertThat(arm.path("rotation")[1].path("v").map { it.asDouble() }).containsExactly(-150.0, 0.0, 20.0)
        assertThat(arm.path("position")[0].path("v").map { it.asDouble() }).containsExactly(0.0, 0.0, 0.0)
        assertThat(arm.path("scale")[0].path("v").map { it.asDouble() }).containsExactly(1.0, 1.0, 1.0)
    }

    @Test
    fun `aliases signed coordinates rounding loop modes and outgoing step survive CLI export`() {
        val input = edited { model ->
            (model.path("outliner")[2] as ObjectNode).put("name", "RightArm")
            val animation = model.path("animations")[0] as ObjectNode
            animation.put("loop", "loop").put("name", "SwordSwing")
            val keys = frames(model)
            (keys[0] as ObjectNode).put("interpolation", "step")
            (keys[1] as ObjectNode).put("time", 0.225)
            (keys[1].path("data_points")[0] as ObjectNode).put("y", "return -30;")
            (keys[3].path("data_points")[0] as ObjectNode).put("x", "-2").put("y", "3").put("z", "-4")
            (keys[4].path("data_points")[0] as ObjectNode).put("x", "1.5").put("y", "0.75").put("z", "2")
            // Keep source order deliberately scrambled; sorting belongs to the importer.
            val first = keys.remove(0)
            keys.add(first)
            val second = animation.deepCopy().put("name", "Rest").put("loop", "hold")
            (model.path("animations") as ArrayNode).add(second)
        }
        val result = run(input)
        assertThat(result.first).describedAs(result.second).isZero()
        assertThat(result.second).contains("hold exports as loop=false")
        val doc = decode()
        assertThat(doc.path("loop").asBoolean()).isTrue()
        assertThat(decode("rest").path("loop").asBoolean()).isFalse()
        val arm = doc.path("bones").path("right_arm")
        assertThat(arm.path("rotation")[1].path("t").asInt()).isEqualTo(5)
        assertThat(arm.path("rotation")[1].path("ease").asText()).isEqualTo("step")
        assertThat(arm.path("rotation")[2].path("ease").asText()).isEqualTo("linear")
        assertThat(arm.path("rotation")[1].path("v")[1].asDouble()).isEqualTo(30.0)
        assertThat(arm.path("position")[0].path("v").map { it.asDouble() }).containsExactly(-2.0, 3.0, 4.0)
        assertThat(arm.path("scale")[0].path("v").map { it.asDouble() }).containsExactly(1.5, 0.75, 2.0)
    }

    @Test
    fun `Bezier is fitted to a supported ease and Catmullrom applies on either side`() {
        val input = edited { model ->
            val keys = frames(model)
            (keys[1] as ObjectNode).put("interpolation", "bezier")
            // Constant endpoint tangents: symmetric ease-in-out is the closest candidate.
            val animation = model.path("animations")[0].deepCopy<ObjectNode>().put("name", "spline")
            (animation.path("animators").elements().next().path("keyframes")[1] as ObjectNode)
                .put("interpolation", "catmullrom")
            (model.path("animations") as ArrayNode).add(animation)
        }
        val result = run(input)
        assertThat(result.first).describedAs(result.second).isZero()
        assertThat(decode().path("bones").path("right_arm").path("rotation")[1].path("ease").asText())
            .isEqualTo("ease_in_out")
        val spline = decode("spline").path("bones").path("right_arm").path("rotation")
        assertThat(spline[1].path("ease").asText()).isEqualTo("catmullrom")
        assertThat(spline[2].path("ease").asText()).isEqualTo("catmullrom")
    }

    @Test
    fun `unknown groups are listed and invalid later animations do not overwrite existing files`() {
        val unknown = edited { model ->
            (model.path("outliner")[0] as ObjectNode).put("name", "elbow")
            (model.path("outliner")[1] as ObjectNode).put("name", "knee")
        }
        val result = run(unknown)
        assertThat(result.first).isEqualTo(2)
        assertThat(result.second).contains("Unknown bones: elbow, knee")
        assertThat(Files.exists(output)).isFalse()

        Files.createDirectories(output)
        Files.writeString(output.resolve("sword-swing.yaml"), "keep me")
        val invalid = edited { model ->
            val second = model.path("animations")[0].deepCopy<ObjectNode>().put("name", "bad")
            (second.path("animators").elements().next().path("keyframes")[0].path("data_points")[0] as ObjectNode)
                .put("x", "query.anim_time * 20")
            (model.path("animations") as ArrayNode).add(second)
        }
        val failure = run(invalid)
        assertThat(failure.first).isEqualTo(2)
        assertThat(failure.second).contains("bad/right_arm/rotation", "not a finite numeric constant")
        assertThat(Files.readString(output.resolve("sword-swing.yaml"))).isEqualTo("keep me")
    }

    @Test
    fun `separate group definitions resolve by uuid and changed pivots are rejected`() {
        val input = edited { model ->
            val definitions = model.putArray("groups")
            model.path("outliner").forEach { node ->
                definitions.add(node.deepCopy<ObjectNode>().apply { remove("children") })
                (node as ObjectNode).remove(listOf("name", "origin", "rotation"))
            }
        }
        val success = run(input)
        assertThat(success.first).describedAs(success.second).isZero()
        assertThat(decode().path("bones").has("right_arm")).isTrue()

        val invalid = edited { model ->
            val arm = model.path("outliner")[0].path("children").first { it.path("name").asText() == "right_arm" }
            (arm.path("origin") as ArrayNode).set(1, mapper.nodeFactory.numberNode(21))
        }
        val failure = run(invalid)
        assertThat(failure.first).isEqualTo(2)
        assertThat(failure.second).contains("right_arm: pivot must be")

        val flat = edited { model ->
            val outliner = model.path("outliner") as ArrayNode
            val children = outliner[0].path("children") as ArrayNode
            val head = children.first { it.path("name").asText() == "head" }
            children.remove(children.indexOf(head))
            outliner.add(head)
        }
        val wrongParent = run(flat)
        assertThat(wrongParent.first).isEqualTo(2)
        assertThat(wrongParent.second).contains("Bone 'head' must be a child of 'body'")
    }

    @Test
    fun `tick collisions fail instead of silently discarding an authored key`() {
        val input = edited { model -> (frames(model)[1] as ObjectNode).put("time", 0.01) }
        val result = run(input)
        assertThat(result.first).isEqualTo(2)
        assertThat(result.second).contains("keyframes collide", "sword-swing/right_arm/rotation")
        assertThat(Files.exists(output)).isFalse()
    }
}
