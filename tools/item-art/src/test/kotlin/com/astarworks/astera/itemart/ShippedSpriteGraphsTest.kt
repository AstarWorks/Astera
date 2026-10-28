package com.astarworks.astera.itemart

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Integration: shipped manifests -> real source loader -> DAG renderer -> committed PNG/font/metadata. */
class ShippedSpriteGraphsTest {
    private val content = Path.of(System.getProperty("astera.contentDir"))
    private val examples = Path.of(System.getProperty("astera.spriteExamplesDir"))

    @Test
    fun `shipped graphs render the committed sprite strip font and metadata`() {
        val defs = SpriteSources.load(content)
        val graphs = defs.filter { it.origin is SpriteSources.Origin.Graph }
        assertThat(graphs).isNotEmpty
        assertThat(Files.readString(content.resolve(SpriteSources.MANIFEST))).doesNotContain("draw:")
        for (def in graphs) {
            val generated = SpriteSources.strip(SpriteSources.render(def))
            val committed = ImageIO.read(content.resolve("${SpriteSources.TEXTURE_DIR}/${def.id}.png").toFile())
            assertThat(generated.width).isEqualTo(def.size * def.frames)
            assertThat(generated.getRGB(0, 0, generated.width, generated.height, null, 0, generated.width))
                .describedAs("${def.id} pixels").containsExactly(*committed.getRGB(0, 0, committed.width, committed.height, null, 0, committed.width))
            assertThat(Files.readString(content.resolve("${SpriteSources.FONT_DIR}/${def.id}.json")))
                .isEqualTo(SpriteSources.fontJson(def.id, def.frames, def.size))
            assertThat(Files.readString(content.resolve("${SpriteSources.META_DIR}/${def.id}.yaml")))
                .isEqualTo(SpriteSources.metaYaml(def.id, def.frames, def.size))
        }
    }

    @Test
    fun `authoring recipes and node catalog render deterministically when scrubbed backwards`() {
        val defs = SpriteSources.load(examples)
        assertThat(defs.map { it.id }).contains("expanding-ring", "rotating-magic-circle", "slash-arc-with-trail", "node-catalog")
        val covered = mutableSetOf<String>()
        for (def in defs) {
            val graph = (def.origin as SpriteSources.Origin.Graph).graph
            covered += graph.nodes.values.map { it.type }
            val forward = graph.renderer(def.size, def.frames)
            val frames = (0 until def.frames).map { forward.image(it).rgba.copyOf() }
            val scrub = graph.renderer(def.size, def.frames)
            for (frame in (0 until def.frames).reversed()) {
                assertThat(scrub.image(frame).rgba).describedAs("${def.id} frame $frame").containsExactly(*frames[frame])
                for (id in graph.nodes.keys) {
                    val value = scrub.intermediate(id, frame)
                    if (value is SpriteGraph.Image) assertThat(value.rgba.all { it.isFinite() }).isTrue()
                }
            }
        }
        assertThat(covered).containsAll(SpriteGraph.SPECS.keys)
    }

    @Test
    fun `real manifest loader rejects broken authored graphs`(@TempDir dir: Path) {
        val doc = Yaml().load<Map<String, Any>>(Files.readString(content.resolve(SpriteSources.MANIFEST)))
        @Suppress("UNCHECKED_CAST")
        val shipped = (doc.getValue("sprites") as Map<String, Map<String, Any>>).getValue("spark")
        val mutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { it["type"] = "shape.typo" },
            { it["params"] = mapOf("unknown" to 1) },
            { it["params"] = mapOf("color" to 123) },
            { it["params"] = mapOf("expression" to "undefined(x)") },
            { it["params"] = mapOf("expression" to "missing+1") },
            { it["params"] = mapOf("color" to mapOf("from" to "out.image")) },
        )
        val manifest = dir.resolve(SpriteSources.MANIFEST)
        Files.createDirectories(manifest.parent)
        fun load(nodes: Map<String, Any?>) {
            val entry = shipped + ("graph" to mapOf("nodes" to nodes))
            Files.writeString(manifest, Yaml().dump(mapOf("sprites" to mapOf("spark" to entry))))
            SpriteSources.load(dir)
        }
        for (mutate in mutations) {
            val node = mutableMapOf<String, Any?>("type" to "shape.field")
            mutate(node)
            assertThatThrownBy { load(mapOf("field" to node, "out" to mapOf("type" to "output", "inputs" to mapOf("image" to "field.image")))) }
                .isInstanceOf(RuntimeException::class.java)
        }
        for (edge in listOf("missing.image", "field.typo", "out.image")) {
            assertThatThrownBy {
                load(mapOf("field" to mapOf("type" to "shape.circle"), "out" to mapOf("type" to "output", "inputs" to mapOf("image" to edge))))
            }.isInstanceOf(RuntimeException::class.java)
        }
        Files.writeString(manifest, "sprites: {spark: {draw: spark}}")
        assertThatThrownBy { SpriteSources.load(dir) }.hasMessageContaining("unknown keys")
        Files.writeString(manifest, "sprites: {}\nsprites: {}\n")
        assertThatThrownBy { SpriteSources.load(dir) }.isInstanceOf(RuntimeException::class.java)
    }
}
