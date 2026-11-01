package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Scenario 2: a caster's fire skill lands and applies a status effect that ticks and expires. */
class WandStatusScenarioTest {

    @Test
    fun `flame burst applies burn which ticks damage and expires`() {
        // flame-burst applies burn with `when: rand() < burn_chance` (burn_chance = 0.85), so over 16 casts the expectation is
        // 16 * 0.85 = 13.6 burns. The sim is deterministic: rand() is seeded from (cast seed, row seed, firing tick, victim id),
        // so for these 16 fixed seeds (DEFAULT_SEED + k) at the current hit timing the draws are fixed numbers, not a
        // distribution. The seeds that draw below 0.85 are exactly EXPECTED_BURNING_K, 12 of 16 (a plausible sample of the
        // 13.6 expectation: P(X = 12) is 0.13 and P(X <= 12) is 0.21 for a fair 0.85 roll). The hit tick is part of the key, so a change to
        // the skill's hit timing or burn_chance re-draws every seed and this set must then be re-derived (the failure message
        // lists the new set). Every cast must hit, and each burning seed must tick damage and then expire.
        val seeds = (0L until 16L).map { GameSim.DEFAULT_SEED + it }
        val burnedKs = mutableListOf<Int>()
        for ((k, seed) in seeds.withIndex()) {
            scenario("wand-applies-burn-$seed", seed = seed) {
                val caster = player("caster", team = "red")
                val target = mob("dummy", at = Vec3(0.0, groundY, 8.0), team = "blue")
                caster.holds("fire-staff")
                caster.faces(target)
                // Keep the dummy alive through repeated casts so the status effect can be observed.
                sim.setMaxHealth(target, 200.0)

                val container = sim.services.getOrThrow<IStatusEffectContainer>()
                val burn = StatusEffectId("burn")

                caster.cast("flame-burst")
                advance(85) // > the skill's 80-tick cooldown; the hit lands on the next tick
                assertThat(eventsOf<DamageDealt>().filter { it.target == target.id }).`as`("seed $seed hit").isNotEmpty()
                val applied = eventsOf<StatusApplied>().filter { it.target == target.id && it.effect.spec.id == burn }
                if (applied.isEmpty()) return@scenario
                burnedKs += k
                // One hit lands one StatusApplied (not two) and one burn stack, stamped with the sim tick.
                assertThat(applied).`as`("seed $seed").hasSize(1)
                assertThat(applied.single().effect.currentStacks).isEqualTo(1)
                assertThat(applied.single().atTick).isLessThanOrEqualTo(sim.tick)

                // Burn ticks through the health gateway (rerouted DoT) every 20 ticks, then expires.
                advance(120)
                val dotTicks = eventsOf<PlayerHealthChanged>().filter {
                    it.playerId == target.id && it.source.toString().contains("burn")
                }
                assertThat(dotTicks).`as`("seed $seed burn ticks").isNotEmpty()
                assertThat(eventsOf<StatusExpired>().any { it.target == target.id && it.effectId == burn }).isTrue()
                assertThat(container.has(target.id, burn)).isFalse()
            }
        }
        assertThat(burnedKs).`as`("burning k (seed = DEFAULT_SEED + k) of ${seeds.size} casts, burn_chance 0.85").isEqualTo(EXPECTED_BURNING_K)
    }

    @Test
    fun `a victim killed by the hit gets no status applied`() {
        scenario("wand-kill-skips-status") {
            val caster = player("caster", team = "red")
            val target = mob("dummy", at = Vec3(0.0, groundY, 8.0), team = "blue")
            caster.holds("fire-staff")
            caster.faces(target)
            sim.setMaxHealth(target, 1.0) // the 12-damage hit kills it before apply_status runs

            repeat(4) {
                caster.cast("flame-burst")
                advance(85)
            }

            assertThat(eventsOf<DamageDealt>().filter { it.target == target.id }).isNotEmpty()
            assertThat(eventsOf<StatusApplied>().filter { it.target == target.id }).isEmpty()
        }
    }

    private companion object {
        /** Indices k (seed = DEFAULT_SEED + k) whose burn roll lands, derived by running the 16 seeds once; see the test comment. */
        val EXPECTED_BURNING_K = listOf(0, 1, 3, 4, 6, 7, 10, 11, 12, 13, 14, 15)
    }
}
