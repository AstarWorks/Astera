package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** WP-3: damage flags reach the gateway, `has_status` / `is_ally` conditions, strict damage keys, window absorb/scale. */
class DamageControlOpsScenarioTest {

    private val enemy = simVictim(1)
    private val friend = simVictim(2)

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(id: String, gameplay: String): Pair<String, String> =
        id to """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: []
            gameplay: ${flat(gameplay)}
            presentation: {timeline: []}
        """.trimIndent()

    private fun damageOn(extra: String, target: String = "victim") = skill(
        "t-dmg",
        """{timeline: [{id: t0, at: 0, do: [{id: sel, op: hit, frames: [caster], sphere: {at: caster, radius: 8}, window: once}]}],
            "on": {hit: [{id: d, op: damage, target: $target, components: [{kind: physical, amount: 10}], $extra}]}}""",
    )

    @Test
    fun `ignore flags reach the gateway`() {
        val sim = CastSim(victims = mapOf(enemy to Vec3(0.0, 0.0, 2.0)), extraSkills = mapOf(damageOn("ignore_armor: true, ignore_invulnerability: true")))
        sim.cast("t-dmg", 3)
        val hit = sim.dealt.single { it.victim == enemy }
        assertThat(hit.ignoreArmor).isTrue()
        assertThat(hit.ignoreInvulnerability).isTrue()
    }

    @Test
    fun `has_status gates on the target stacks`() {
        val gate = skill(
            "t-hs",
            """{timeline: [{id: t0, at: 0, do: [{id: h, op: has_status, target: caster, status: charge, min_stacks: 2,
                then: [{id: m, op: status, status: marker, target: caster, stacks: 1, duration_ticks: 10}]}]}]}""",
        )
        val with = CastSim(victims = emptyMap(), casterStacks = mapOf("charge" to 2), extraSkills = mapOf(gate))
        with.cast("t-hs", 3)
        assertThat(with.statuses.filter { it.third == "marker" }).hasSize(1)
        val without = CastSim(victims = emptyMap(), casterStacks = mapOf("charge" to 1), extraSkills = mapOf(gate))
        without.cast("t-hs", 3)
        assertThat(without.statuses.filter { it.third == "marker" }).isEmpty()
    }

    @Test
    fun `has_status reads another entity`() {
        val gate = skill(
            "t-hsv",
            """{timeline: [{id: t0, at: 0, do: [{id: h, op: has_status, target: {kind: enemies, around: caster, radius: 10}, status: bleed,
                then: [{id: m, op: status, status: marker, target: caster, stacks: 1, duration_ticks: 10}]}]}]}""",
        )
        val sim = CastSim(victims = mapOf(enemy to Vec3(0.0, 0.0, 2.0)), entityStacks = mapOf(enemy to mapOf("bleed" to 1)), extraSkills = mapOf(gate))
        sim.cast("t-hsv", 3)
        assertThat(sim.statuses.filter { it.third == "marker" }).hasSize(1)
    }

    @Test
    fun `is_ally separates teammates from enemies`() {
        val sim = CastSim(
            victims = mapOf(enemy to Vec3(0.0, 0.0, 2.0), friend to Vec3(0.0, 0.0, 3.0)),
            allies = setOf(friend),
            extraSkills = mapOf(
                skill(
                    "t-ally",
                    """{timeline: [{id: t0, at: 0, do: [{id: sel, op: hit, frames: [caster], sphere: {at: caster, radius: 8}, window: once}]}],
                        "on": {hit: [{id: a, op: is_ally, target: victim, else: [{id: d2, op: damage, target: victim, components: [{kind: physical, amount: 10}]}]}]}}""",
                ),
            ),
        )
        sim.cast("t-ally", 3)
        assertThat(sim.dealt.map { it.victim }).containsExactly(enemy)
    }

    @Test
    fun `unknown damage keys fail at load`() {
        assertThatThrownBy {
            CastSim(victims = emptyMap(), extraSkills = mapOf(damageOn("bogus_key: 1"))).cast("t-dmg", 1)
        }.hasMessageContaining("bogus_key")
    }

    @Test
    fun `window absorb negates and scale multiplies`() {
        fun win(w: String) = skill(
            "t-win",
            """{timeline: [{id: t0, at: 0, do: [{id: w, op: window, ticks: 20, $w}]}]}""",
        )
        val absorb = CastSim(victims = mapOf(enemy to Vec3(0.0, 0.0, 2.0)), extraSkills = mapOf(win("absorb: true")))
        absorb.start("t-win")
        absorb.step(2)
        assertThat(absorb.incoming(enemy, 12.0)).isEqualTo(0.0)
        val half = CastSim(victims = mapOf(enemy to Vec3(0.0, 0.0, 2.0)), extraSkills = mapOf(win("scale: 0.5")))
        half.start("t-win")
        half.step(2)
        assertThat(half.incoming(enemy, 12.0)).isEqualTo(0.5)
        assertThatThrownBy {
            CastSim(victims = emptyMap(), extraSkills = mapOf(win("absorb: true, scale: 0.5"))).cast("t-win", 1)
        }.hasMessageContaining("absorb")
    }
}
