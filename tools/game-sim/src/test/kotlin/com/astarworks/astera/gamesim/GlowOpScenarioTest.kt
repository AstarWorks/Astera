package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP6-B `glow` (v1 WorldMechanics.glow, WM:585): real runner, real decoder, the glow driver over a recording gateway.
 * v1 rules: only living players glow; a later glow on the same target OVERWRITES the end time (last writer wins, even a
 * shorter one); the outline switches off when the surviving end time is reached.
 */
class GlowOpScenarioTest {

    private val player = simVictim(1)
    private val mob = simVictim(2)

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(id: String, rows: String): Pair<String, String> =
        id to """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: []
            gameplay: {timeline: []}
            presentation: ${flat("{timeline: [$rows]}")}
        """.trimIndent()

    private fun glowRow(at: Int, ticks: Int) =
        "{id: g$at, at: $at, do: [{id: glow$at, op: glow, target: {enemies: {around: caster, radius: 10}}, ticks: $ticks}]}"

    private fun sim(rows: String, nonPlayers: Set<UUID> = emptySet()) = CastSim(
        victims = mapOf(player to Vec3(0.0, 0.0, 3.0), mob to Vec3(0.0, 0.0, 4.0)),
        extraSkills = mapOf(skill("t-glow", rows)),
        nonPlayers = nonPlayers,
    )

    @Test
    fun `a later glow overwrites the end time and only the last one switches it off`() {
        val sim = sim(glowRow(0, 20) + "," + glowRow(5, 30))
        sim.cast("t-glow", 60)
        val mine = sim.glows.filter { it.second == player }
        assertThat(mine.map { it.first to it.third })
            .describedAs("on at 0, on again at 5, off once at 5 + 30 (not at 0 + 20)")
            .containsExactly(0 to true, 5 to true, 35 to false)
    }

    @Test
    fun `a shorter later glow still wins the end time`() {
        val sim = sim(glowRow(0, 40) + "," + glowRow(5, 10))
        sim.cast("t-glow", 60)
        val mine = sim.glows.filter { it.second == player }
        assertThat(mine.map { it.first to it.third }).containsExactly(0 to true, 5 to true, 15 to false)
    }

    @Test
    fun `only living players glow`() {
        val sim = sim(glowRow(0, 20), nonPlayers = setOf(mob))
        sim.cast("t-glow", 40)
        assertThat(sim.glows.map { it.second }.toSet()).describedAs("the mob never glows").containsExactly(player)
        assertThat(sim.glows.map { it.first to it.third }).containsExactly(0 to true, 20 to false)
    }
}
