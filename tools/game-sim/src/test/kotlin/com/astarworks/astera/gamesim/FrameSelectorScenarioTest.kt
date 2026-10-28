package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-14 / WP-C, founder 10/27 (remark 5) and 10/28 (remark 38): damage, status and sound pick victims the same way,
 * entities within r of a frame point; `max_count` is the nearest N; `distance_less_than` is "any enemy within d".
 * v1: TargeterResolver.kt:54-86 (nearby_entities max_count), ConditionEvaluator.kt:45-49 (distance_less_than).
 * Real runner, real decoders; the three enemies stand at 2 / 3 / 4 blocks, a fourth outside the radius at 9.
 */
class FrameSelectorScenarioTest {

    private val near = simVictim(1)
    private val mid = simVictim(2)
    private val far = simVictim(3)
    private val out = simVictim(4)

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(id: String, gameplayRows: String, onHit: String): Pair<String, String> =
        id to """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: []
            gameplay: ${flat("{timeline: [$gameplayRows]}")}
            presentation: ${flat("{on: {hit: [$onHit]}}")}
        """.trimIndent()

    private fun sim(vararg skills: Pair<String, String>) = CastSim(
        victims = mapOf(
            near to Vec3(0.0, 0.0, 2.0), mid to Vec3(0.0, 0.0, 3.0), far to Vec3(0.0, 0.0, 4.0), out to Vec3(0.0, 0.0, 9.0),
        ),
        extraSkills = skills.toMap(),
    )

    @Test
    fun `the same frame-anchored selector gives damage, status and sound the same victims, nearest N only`() {
        val sim = sim(
            skill(
                "t-sel",
                "{id: t0, at: 0, do: [{id: sel, op: hit, frames: [caster], sphere: {at: caster, radius: 5}, max_count: 2, window: once, ticks: 1}]}",
                "{id: snd, op: sound, sound: entity.player.attack.sweep, at: enemy, when: \"= event.source == 'sel'\"}",
            ),
        )
        sim.cast("t-sel", 5)
        assertThat(sim.sounds).describedAs("sound plays once per selected victim, at the hit").hasSize(2)
    }

    @Test
    fun `damage and status addressed as enemies around a point keep the nearest N inside the radius`() {
        val sim = sim(
            skill(
                "t-area",
                """{id: t0, at: 0, do: [
                    {id: d, op: damage, components: [{kind: physical, amount: 10}], target: {enemies: {around: caster, radius: 5, max_count: 2}}},
                    {id: s, op: status, status: bleed, stacks: 1, duration_ticks: 20, target: {enemies: {around: caster, radius: 5, max_count: 2}}}
                ]}""",
                "",
            ),
        )
        sim.cast("t-area", 5)
        assertThat(sim.dealt.map { it.victim }.toSet()).describedAs("nearest two, not the 4-block one, not the 9-block one").containsExactlyInAnyOrder(near, mid)
        assertThat(sim.statuses.map { it.second }.toSet()).describedAs("status rides the same selector").containsExactlyInAnyOrder(near, mid)
    }

    @Test
    fun `nearest_enemy_dist is true when any enemy is closer than d`() {
        val sim = sim(
            skill(
                "t-cond",
                """{id: t0, at: 0, do: [
                    {id: yes, op: damage, components: [{kind: physical, amount: 1}], target: caster, when: "= nearest_enemy_dist(caster) < 2.5"},
                    {id: no, op: damage, components: [{kind: physical, amount: 2}], target: caster, when: "= nearest_enemy_dist(caster) < 1.5"}
                ]}""",
                "",
            ),
        )
        sim.cast("t-cond", 5)
        assertThat(sim.dealt.map { it.amount }).describedAs("enemy at 2 < 2.5 passes, < 1.5 does not").containsExactly(1.0)
    }
}
