package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.IBlockKeyCatalog
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Founder 2026-10-28: 「黙って捨てる挙動が一番よくないでしょ」. The sound, particle and entity-query ports are bound to the
 * world of the cast (no "last seen world"), a held skill ends on `release`, and an unknown block key fails the load.
 */
class SkillPortWorldScenarioTest {

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(id: String, gameplay: String, presentation: String): Pair<String, String> =
        id to """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: []
            gameplay: ${flat(gameplay)}
            presentation: ${flat(presentation)}
        """.trimIndent()

    private val voice = skill(
        "t-voice",
        gameplay = """{timeline: [{id: t0, at: 0, do: [{id: sel, op: hit, frames: [caster], sphere: {at: caster, radius: 3.0}, window: once}]}]}""",
        presentation = """{timeline: [
            {id: s, at: 1, do: [{id: snd, op: sound, sound: "astera:test/voice", at: caster}]},
            {id: e, at: 1, do: [{id: em, op: emit, at: caster, particle: end_rod}]}
        ]}""",
    )

    @Test
    fun `a cast in world B plays its sound, particles and queries in B`() {
        val b = WorldId("world_b")
        val sim = CastSim(victims = mapOf(simVictim(1) to Vec3(0.0, 0.0, 1.0)), extraSkills = mapOf(voice), castWorld = b)
        sim.cast("t-voice", 4)
        assertThat(sim.sounds.map { it.second }).contains("astera:test/voice")
        assertThat(sim.soundWorlds).isNotEmpty.containsOnly(b)
        assertThat(sim.particleWorlds).isNotEmpty.containsOnly(b)
        assertThat(sim.queryWorlds).isNotEmpty.containsOnly(b)
        val other = CastSim(victims = emptyMap(), extraSkills = mapOf(voice), castWorld = WorldId("world_a"))
        other.cast("t-voice", 4)
        assertThat(other.soundWorlds).isNotEmpty.containsOnly(WorldId("world_a"))
    }

    @Test
    fun `hold then release ends the until release state`() {
        val held = skill(
            "t-held",
            gameplay = "{timeline: [{id: t0, at: 0, do: []}, {id: t60, at: 60, do: []}]}",
            presentation = """{timeline: [{id: s, at: 1, do: [{id: snd, op: sound, sound: "astera:test/held", at: caster, every: 2, until: release}]}]}""",
        )
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(held))
        val cast = sim.start("t-held")
        sim.step(11)
        val whileHeld = sim.sounds.count { it.second == "astera:test/held" }
        assertThat(whileHeld).describedAs("sounds every 2 ticks while held").isGreaterThanOrEqualTo(4)
        assertThat(sim.kit.runner.isRunning(cast)).isTrue()
        assertThat(sim.kit.runner.raiseAllOf(SIM_CASTER, "release", sim.now)).isEqualTo(1)
        sim.step(31)
        assertThat(sim.sounds.count { it.second == "astera:test/held" })
            .describedAs("no more sounds after release (one may already be queued in the release tick)")
            .isLessThanOrEqualTo(whileHeld + 1)
        assertThat(sim.kit.runner.raiseAllOf(SIM_CASTER, "release", sim.now)).describedAs("released once, nothing left to release").isEqualTo(0)
        assertThat(sim.kit.runner.diagnostics()).isEmpty()
    }

    @Test
    fun `an unknown block key is a load error naming the skill and the path`() {
        val dir = Files.createTempDirectory("sim-block-skills")
        val (id, yaml) = skill(
            "t-bad-block",
            gameplay = """{timeline: [{id: t0, at: 0, do: [{id: put, op: set_block, block: no_such_block}]}]}""",
            presentation = "{timeline: []}",
        )
        Files.createDirectories(dir.resolve(id))
        Files.writeString(dir.resolve(id).resolve("skill.yaml"), yaml)
        val catalog = SkillEngineRegistries.catalog(emptyMap(), IBlockKeyCatalog { it == "oak_planks" })
        val report = SkillEngineContents(catalog).reload(dir)
        assertThat(report.failed).isEqualTo(1)
        val failure = report.failures.single()
        assertThat(failure.file.toString()).contains("t-bad-block")
        assertThat(failure.message).contains("no_such_block").contains("unknown block key")
        assertThat(catalog.get("t-bad-block")).isNull()
    }
}
