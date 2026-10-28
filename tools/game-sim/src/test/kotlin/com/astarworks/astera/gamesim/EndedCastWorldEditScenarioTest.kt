package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder bug 2026-10-29 (dev match): after a while nobody could kill anything. A cast that ends while its ripple-delayed
 * `break_area` edits are still queued must not poison the tick (`cast N is not running` every tick), one cast's failing
 * world edit must not stall the others, and `pos(victim)` of a knockback must resolve after the same handler killed the victim.
 */
class EndedCastWorldEditScenarioTest {

    private val victim = simVictim(1)

    private fun breaker(): Pair<String, String> = "t-breaker" to """
        apiVersion: "astera/v2"
        kind: Skill
        id: t-breaker
        item: pickaxe
        meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
        gameplay: {timeline: [{id: t0, at: 0, do: [{id: break, op: break_area, shape: sphere, radius: 2, tool_class: PICKAXE, block_damage: 100}]}]}
    """.trimIndent()

    @Test
    fun `a cast that ended with queued break_area edits finishes them and other casts keep damaging`() {
        val sim = CastSim(
            victims = mapOf(victim to Vec3(0.0, 0.0, 1.5)),
            extraSkills = mapOf(breaker()),
            worldAsksCastOwner = true,
        )
        val breakCast = sim.start("t-breaker")
        sim.step(3)
        assertThat(sim.kit.runner.isRunning(breakCast)).describedAs("the breaker ended on its first tick").isFalse()
        sim.start("dagger-stab")
        sim.step(40)

        assertThat(sim.kit.runner.diagnostics()).describedAs("no op failed").isEmpty()
        assertThat(sim.blockStrikes).describedAs("the ripple of the ended cast still landed").isNotEmpty()
        assertThat(sim.dealt.filter { it.victim == victim }).describedAs("the other cast damaged the target").isNotEmpty()
    }

    @Test
    fun `a failing world edit ends only its cast, logs once and the tick goes on`() {
        var thrown = 0
        val sim = CastSim(
            victims = mapOf(victim to Vec3(0.0, 0.0, 1.5)),
            extraSkills = mapOf(breaker()),
            onBlockStrike = { thrown++; error("gateway down") },
        )
        val breakCast = sim.start("t-breaker")
        sim.step(5)
        sim.start("dagger-stab")
        sim.step(40)

        assertThat(thrown).describedAs("the cast's other queued edits were dropped after the first failure").isEqualTo(1)
        val failures = sim.kit.runner.diagnostics()
        assertThat(failures).describedAs("exactly one loud diagnostic").hasSize(1)
        assertThat(failures[0].cast).isEqualTo(breakCast)
        assertThat(failures[0].opId).isEqualTo("break")
        assertThat(failures[0].message).contains("t-breaker").contains("gateway down")
        assertThat(sim.dealt.filter { it.victim == victim }).describedAs("the other cast still damaged the target").isNotEmpty()
    }

    @Test
    fun `knockback after a killing hit still resolves pos(victim)`() {
        val sim = CastSim(victims = mapOf(victim to Vec3(0.0, 0.0, 1.5)), victimsDieOnDamage = true)
        sim.cast("dagger-stab", 10) // asserts no diagnostics: no "unknown frame or point 'victim'"
        assertThat(sim.dealt.filter { it.victim == victim }).isNotEmpty()
    }
}
