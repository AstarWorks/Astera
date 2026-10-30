package com.astarworks.astera.resourcepack

import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.compose.ContentSpriteCatalog
import com.astarworks.astera.core.application.skillengine.part.PartParser
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Integration: real content, vanilla client shaders, pack builder and production skill loading. */
class ShippedShaderEffectsPackTest {
    private val content = Path.of(System.getProperty("astera.contentDir"))
    private val json = ObjectMapper()
    private val pack by lazy { ResourcePackBuilder(content).collect() }

    private fun catalog(tables: Map<String, ItemFrames>) =
        SkillEngineRegistries.catalog(
            tables,
            (PartParser.loadDirectory(content.resolve("skill-parts")) as Result.Success).value,
            ContentSpriteCatalog.load(content.resolve("sprites")),
            (com.astarworks.astera.core.application.skillengine.compose.drycast.ClutterContext.load(
                content, (SkillEngineRegistries.bodySkeleton(content.resolve("frames")) as Result.Success).value,
            ) { null } as Result.Success).value,
        )

    @TempDir
    lateinit var temp: Path

    @Test
    fun `real pack composes three isolated effects and preserves the skin rig shader`() {
        for ((stage, expected) in mapOf(
            "vsh" to "15b468dc5815d84e48c096865313c88e820e40c531fecd7288c162142b658dd7",
            "fsh" to "1fb188d28a7a644f1cb14ae1e86b2894633f089e70aad773dda716f2ca12cf8e",
        )) {
            // Baseline before ADR-0305: ignore blank-line/indentation changes from composing multiple snippets.
            val source = pack.getValue("assets/minecraft/shaders/core/entity.$stage").toString(Charsets.UTF_8)
            val normalized = source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
            assertThat(hash).describedAs("unchanged skin rig $stage").isEqualTo(expected)
        }
        val vertex = pack.getValue("assets/minecraft/shaders/core/item.vsh").toString(Charsets.UTF_8)
        val fragment = pack.getValue("assets/minecraft/shaders/core/item.fsh").toString(Charsets.UTF_8)
        assertThat(vertex).contains("UV2.x == 240 && UV2.y == 224", "254.0 / 255.0", "UV2.x == 208 && UV2.y == 176")
        assertThat(fragment).contains("asteraWeaponTone", "GameTime * 1200.0", "asteraDissolve", "asteraFlow", "asteraEmissive")
        assertThat(vertex + fragment).doesNotContain("\${", "@@ hook:", "# Astera")
        val ledger = json.readTree(pack.getValue("assets/astera/shader-effects/markers.json"))
        assertThat(ledger.any { it.path("effect").asText() == "vfx-surface" && it.path("value").asText() == "208:176" }).isTrue()
        val original = json.readTree(pack.getValue("assets/astera/items/iron-warden-chestplate.json"))
        assertThat(original.path("model").path("tints").isMissingNode).isTrue()
        assertThat(ledger.map { it.path("channel").asText() + ":" + it.path("value").asText() }).doesNotHaveDuplicates()
        for ((id, tint) in mapOf("dissolve" to 66049, "uv-flow" to 66050, "emissive-pulse" to 66051)) {
            assertThat(ledger.any { it.path("effect").asText() == id && it.path("value").asInt() == tint }).isTrue()
            val definition = json.readTree(pack.getValue("assets/astera/items/shader-effects/$id/iron-warden-chestplate.json"))
            assertThat(definition.path("model").path("tints")[0].path("value").asInt()).isEqualTo(tint)
            assertThat(pack).containsKey("assets/astera/models/shader-effects/$id/generated/items/iron-warden-chestplate.json")
        }
    }

    @Test
    fun `shipped VFX demos load and unknown effect fails content load`() {
        val (tables, _) = SkillEngineRegistries.frameTables(content.resolve("frames"))
        val catalog = catalog(tables)
        val contents = SkillEngineContents(catalog)
        val report = contents.reload(content.resolve("skills"))
        assertThat(report.failures).isEmpty()
        for (id in listOf("dissolve", "uv-flow", "emissive-pulse")) assertThat(catalog.get("demo-shader-$id")).isNotNull()
        Files.createSymbolicLink(temp.resolve("skills"), content.resolve("skills"))
        Files.createSymbolicLink(temp.resolve("shader-patches"), content.resolve("shader-patches"))
        Files.createDirectories(temp.resolve("vfx"))
        val demo = Files.readString(content.resolve("vfx/demo-shader-dissolve.yaml"))
            .replace("shader_effect: dissolve", "shader_effect: missing-effect")
        Files.writeString(temp.resolve("vfx/demo-shader-dissolve.yaml"), demo)
        val rejected = SkillEngineContents(catalog(tables)).reload(temp.resolve("skills"))
        assertThat(rejected.failed).isGreaterThan(0)
        assertThat(rejected.failures.any { it.toString().contains("unknown shader_effect 'missing-effect'") }).isTrue()
    }

    @Test
    fun `shipped shader pack has no dangling references`() {
        assertThat(PackReferenceCheck.check(pack, null).problems).isEmpty()
    }

    @Test
    fun `duplicate markers fail the real pack build loudly`() {
        Files.list(content).use { paths -> paths.forEach { source ->
            if (source.fileName.toString() != "shader-patches") Files.createSymbolicLink(temp.resolve(source.fileName), source)
        } }
        val sourceRoot = content.resolve("shader-patches")
        Files.walk(sourceRoot).use { paths -> paths.forEach { source ->
            val target = temp.resolve("shader-patches").resolve(sourceRoot.relativize(source))
            if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
        } }
        val effect = temp.resolve("shader-patches/effects/uv-flow/effect.yaml")
        Files.writeString(effect, Files.readString(effect).replace("66050", "66049"))
        assertThatThrownBy { ResourcePackBuilder(temp).collect() }.hasMessageContaining("already claimed")
    }
}
