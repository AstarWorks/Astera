package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

/**
 * SKL-09: one real shipped skill per ported grammar gap, run by the shared [CastSim] (real runner, fake ports).
 */
class PortedGrammarScenarioTest {

    private val caster: UUID = SIM_CASTER
    private fun victim(n: Int): UUID = simVictim(n)
    private fun Sim(victims: Map<UUID, Vec3>, casterStacks: Map<String, Int> = emptyMap(), blockAtZ: Double? = null) = CastSim(victims, casterStacks, blockAtZ)

    @Test
    fun `header meta is decoded onto the compiled skill`() {
        val sim = Sim(emptyMap())
        val meta = sim.catalog.get("bastion-greatsword-s2")!!.def.meta
        assertThat(meta.cooldownTicks).isEqualTo(100)
        assertThat(meta.resourceCost).isEqualTo(30)
        assertThat(meta.name["en"]).isEqualTo("Judgement Rush")
        assertThat(meta.tags).contains("charge")
    }

    @Test
    fun `delay op and ignore_invulnerability - orrery s3 lands four delayed hits through i-frames`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 6.0)))
        sim.cast("orrery-s3", 200)
        assertThat(sim.dealt.map { it.tick }).describedAs("delays 45/60/72/143 after the beam hit at tick 0").containsExactly(45, 60, 72, 143)
        assertThat(sim.dealt).allMatch { it.ignoreInvulnerability }
    }

    @Test
    fun `max_count - edict s1 sector hit takes only the nearest victim`() {
        val near = victim(1)
        val far = victim(2)
        val sim = Sim(mapOf(near to Vec3(0.0, 0.0, 1.5), far to Vec3(0.0, 0.0, 2.5)))
        sim.cast("edict-s1", 40)
        assertThat(sim.dealt.map { it.victim }.toSet()).describedAs("hit cap of 1 spares the far victim").containsExactly(near)
        assertThat(sim.dealt.map { it.amount }).containsExactly(40.0, 55.0)
    }

    @Test
    fun `per_stack - bastion s2 adds half a point per charge stack and the last blow consumes it`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 2.0)), casterStacks = mapOf("charge" to 10))
        sim.cast("bastion-greatsword-s2", 80)
        assertThat(sim.dealt.map { it.amount }).containsExactly(40.0, 45.0, 65.0)
        assertThat(sim.removed).containsExactly(caster to "charge")
    }

    @Test
    fun `explode at a point - fireball bursting on a block hurts the victim beside the point`() {
        val v = victim(1)
        val sim = Sim(mapOf(v to Vec3(0.0, 0.0, 6.0)), blockAtZ = 4.5)
        sim.cast("fireball-cast", 60)
        // v1 truth (recorded harness, d41b82f1e SimWorldPorts.kt SimExplosionGateway: distance = |feet + (0, hitboxHeight/2, 0) - centre|,
        // falloff = 1 - distance / radius): the bolt leaves the eye (y 1.62) and bursts on the block at z 4.5; the victim's body
        // centre (feet + 0.9) stands at z 6, radius 2.5. Same rule that yields the recorded fireball-cast total 17.6.
        val falloff = 1.0 - kotlin.math.sqrt(1.5 * 1.5 + 0.72 * 0.72) / 2.5
        assertThat(sim.dealt.map { it.victim }).containsExactly(v)
        assertThat(sim.dealt.single().amount).isCloseTo(27.5 * falloff, org.assertj.core.data.Offset.offset(0.05))
    }

    @Test
    fun `block_damage - flame burst chips blocks at the impact`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 6.0)))
        sim.cast("flame-burst", 80)
        assertThat(sim.dealt).describedAs("bolt hit the victim").isNotEmpty()
        assertThat(sim.blockStrikes).describedAs("break_area chip at the contact").isNotEmpty()
        // v1 WorldMechanics.kt:284-291: a cover cell takes block_damage in six integer portions, damage*k/6 - damage*(k-1)/6,
        // at work steps 4, 8, .. 24 (ticks delay+3, +7, .. +23): 35 -> 5, 6, 6, 6, 6, 6 (total 35). flame-burst has a chip op per handler (hit and block); the bolt
        // hits the victim, so each cell takes the schedule once.
        val byCell = sim.blockStrikesByCell.groupBy({ it.first }, { it.second to it.third })
        assertThat(byCell).isNotEmpty()
        byCell.forEach { (cell, strikes) ->
            val ordered = strikes.sortedBy { it.first }
            assertThat(ordered.map { it.second }).describedAs("portions of $cell")
                .containsExactly(5, 6, 6, 6, 6, 6)
            assertThat(ordered.sumOf { it.second }).describedAs("total at $cell").isEqualTo(35)
            val ticks = ordered.map { it.first }.distinct()
            assertThat(ticks).describedAs("six portion ticks at $cell").hasSize(6)
            assertThat(ticks.zipWithNext { x, y -> y - x }).describedAs("portion spacing at $cell").containsOnly(4)
        }
    }

    @Test
    fun `melee miss - sword slash plays the miss sound only when nothing was hit`() {
        val missed = Sim(emptyMap())
        missed.cast("sword-slash", 20)
        assertThat(missed.sounds.map { it.second }).contains("astera:weapons/blade-miss")

        val hit = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 2.0)))
        hit.cast("sword-slash", 20)
        assertThat(hit.dealt).isNotEmpty()
        assertThat(hit.sounds.map { it.second }).doesNotContain("astera:weapons/blade-miss")
    }

    @Test
    fun `snapshot_pose - verdict hammer ego strikes through a frozen caster pose frame`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 2.0)))
        sim.cast("verdict-hammer-ego", 230)
        assertThat(sim.dealt).describedAs("the snapshot-framed sphere hits landed").isNotEmpty()
    }
}
