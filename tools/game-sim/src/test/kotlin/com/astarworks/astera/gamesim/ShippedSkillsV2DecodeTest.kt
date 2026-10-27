package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * SKL-09 (ADR-0301): the real `content/skills` through the real kit (composer, decoder, compiler, CEL) decodes and
 * compiles with zero failures, every skill an item binds (and every `cast_skill` child) is in the catalog, and every
 * skill carries the `meta:` block the cast gate reads.
 */
class ShippedSkillsV2DecodeTest {

    private val content = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")) { "astera.sim.contentDir is not set" })

    private fun itemBoundSkillIds(): Set<String> {
        val id = Regex("""skill_id:\s*['"]?([A-Za-z0-9_\-]+)""")
        return Files.walk(content.resolve("items")).use { s ->
            s.filter { it.toString().endsWith(".yaml") }.toList().flatMap { f -> id.findAll(Files.readString(f)).map { it.groupValues[1] }.toList() }
        }.toSet()
    }

    @Test
    fun `every shipped skill decodes and compiles and every item-bound skill is in the catalog`() {
        val c = Coin2CastWiring.collaborators()
        val catalog = c.newCatalog()
        val report = SkillEngineContents(catalog).reload(c.skillsDir)
        assertThat(report.failed).describedAs("decode / compile failures: ${report.failures}").isZero()

        val bound = itemBoundSkillIds()
        assertThat(bound).describedAs("item bindings found").hasSizeGreaterThan(100)
        assertThat(bound - catalog.ids()).describedAs("item-bound skills missing from the v2 catalog").isEmpty()

        val noMeta = catalog.ids().filter { catalog.get(it)!!.def.meta.name.isEmpty() }
        assertThat(noMeta).describedAs("skills without meta.name").isEmpty()
    }

    @Test
    fun `no v1 flat skill file is left beside the v2 directories`() {
        val flat = Files.list(content.resolve("skills")).use { s -> s.filter { it.toString().endsWith(".yaml") }.map { it.fileName.toString() }.toList() }
        assertThat(flat).describedAs("v1 flat skill yaml").isEmpty()
    }
}
