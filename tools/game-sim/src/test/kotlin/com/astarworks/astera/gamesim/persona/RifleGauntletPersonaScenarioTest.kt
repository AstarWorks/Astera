package com.astarworks.astera.gamesim.persona

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.CriticalHit
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.ScenarioScope
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.scenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0221 persona weapons #3 `vendetta-rifle` (Vendetta Bayonet) and #4 `ember-gauntlet` (Ember Gauntlet):
 * the four skills of each cast through the real item bindings (or `cast` for the sneak+left DEF, which the
 * click DSL cannot press), deal their damage to a dummy, spend/build their resources and stacks, spawn
 * display choreography within the 60-live-displays budget, and leave no display behind.
 */
class RifleGauntletPersonaScenarioTest {

    private val tremor = StatusEffectId("tremor")
    private val bleed = StatusEffectId("bleed")
    private val burn = StatusEffectId("burn")
    private val charge = StatusEffectId("charge")
    private val immunity = StatusEffectId("immunity")

    private fun ScenarioScope.stacks(entity: SimEntity, id: StatusEffectId): Int =
        sim.services.getOrThrow<IStatusEffectContainer>().stacks(entity.id, id)

    /** Gives [entity] [count] stacks of [id] through the real dispatcher (a stand-in for earlier skill casts). */
    private fun ScenarioScope.stack(entity: SimEntity, id: StatusEffectId, count: Int) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            listOf(Mechanic.ApplyStatus(Targeter.Caster, id, IntValue.Literal(600), stackCount = IntValue.Literal(count))),
            MechanicContext(
                actorId = entity.id,
                targetId = null,
                origin = entity.position,
                worldId = entity.world,
                pose = PlayerPose(entity.world, entity.position, entity.eyePosition, entity.direction),
            ),
        )
    }

    /**
     * Runs [block] as a scenario on a fixed seed that rolls no critical hit (10 percent x2 in CombatWiring would
     * double a packet and make HP assertions flaky); the no-crit property is asserted at the end.
     */
    private fun persona(name: String, block: ScenarioScope.() -> Unit) {
        scenario(name, seed = GameSim.DEFAULT_SEED + SEED_OFFSET) {
            peak = 0
            block()
            assertThat(eventsOf<CriticalHit>()).`as`("seed offset $SEED_OFFSET rolled a crit; pick another").isEmpty()
        }
    }

    /** Highest number of live displays seen by [step] in the running scenario (the 60-per-cast budget). */
    private var peak = 0

    /** Steps [ticks] one tick at a time, tracking the live-display [peak]. */
    private fun ScenarioScope.step(ticks: Int) {
        repeat(ticks) {
            advance(1)
            peak = maxOf(peak, sim.displays.active.size)
        }
    }

    /** Distinct skill damage hits that landed on [entity] (DoT ticks are not skill hits). */
    private fun ScenarioScope.hits(entity: SimEntity): Int =
        eventsOf<DamageDealt>().count { it.target == entity.id && it.source is DamageSource.Skill }

    private fun ScenarioScope.glyphs(): Int =
        sim.displays.spawned.count { it.spec.kind is DisplayKind.Text && it.lines.any { line -> line.contains("✦") } }

    private fun ScenarioScope.blocksNamed(prefix: String): Int =
        sim.displays.spawned.count { (it.spec.kind as? DisplayKind.Block)?.blockKey?.startsWith(prefix) == true }

    private fun ScenarioScope.fighters(distance: Double): Pair<SimEntity, SimEntity> {
        val attacker = player("attacker", team = "red")
        val victim = player("victim", at = Vec3(0.0, groundY, distance), team = "blue")
        return attacker to victim
    }

    // ------------------------------------------------------------------ items

    @Test
    fun `both persona items bind the four skills plus the gun reload`() {
        persona("persona-bindings") {
            val items = sim.items.itemRegistry.all().associateBy { it.id.value }
            val rifle = checkNotNull(items["vendetta-rifle"]).skillBindings.associate { it.trigger to it.skillId.value }
            assertThat(rifle).containsEntry(SkillTrigger.LEFT_CLICK, "vendetta-rifle-s1")
                .containsEntry(SkillTrigger.RIGHT_CLICK, "vendetta-rifle-s2")
                .containsEntry(SkillTrigger.SNEAK_RIGHT, "vendetta-rifle-s3")
                .containsEntry(SkillTrigger.SNEAK_LEFT, "vendetta-rifle-def")
                .containsEntry(SkillTrigger.DROP, "pistol-reload")
            val gauntlet = checkNotNull(items["ember-gauntlet"]).skillBindings.associate { it.trigger to it.skillId.value }
            assertThat(gauntlet).containsEntry(SkillTrigger.LEFT_CLICK, "ember-gauntlet-s1")
                .containsEntry(SkillTrigger.RIGHT_CLICK, "ember-gauntlet-s2")
                .containsEntry(SkillTrigger.SNEAK_RIGHT, "ember-gauntlet-s3")
                .containsEntry(SkillTrigger.SNEAK_LEFT, "ember-gauntlet-def")
        }
    }

    // ------------------------------------------------------------------ vendetta-rifle

    @Test
    fun `vendetta S1 dashes in, lands the heavy thrust last and stacks two tremor for free`() {
        persona("persona-vendetta-s1") {
            val (shooter, victim) = fighters(3.0)
            shooter.holds("vendetta-rifle")
            shooter.faces(victim)
            val ammoBefore = ammo(shooter)
            val hpBefore = hp(victim)

            shooter.leftClick()
            step(6)
            assertThat(hits(victim)).`as`("crescent A and B have landed, the thrust has not").isEqualTo(2)
            assertThat(stacks(victim, tremor)).isEqualTo(2)
            step(40)

            assertThat(hits(victim)).`as`("three coins: A, B, thrust").isEqualTo(3)
            assertThat(hpBefore - hp(victim)).isBetween(5.0, 12.0)
            assertThat(ammo(shooter)).`as`("the bayonet is free").isEqualTo(ammoBefore)
            assertThat(shooter.position.z).`as`("dashed forward").isGreaterThan(3.0)
            assertThat(glyphs()).`as`("one flash glyph per beat").isGreaterThanOrEqualTo(3)
            assertThat(blocksNamed("orange")).isGreaterThan(0)
            assertThat(blocksNamed("magenta")).isGreaterThan(0)
            assertThat(peak).isBetween(15, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `vendetta S1 thrust refunds a round`() {
        persona("persona-vendetta-s1-refund") {
            val (shooter, victim) = fighters(3.0)
            shooter.holds("vendetta-rifle")
            shooter.faces(victim)
            shooter.rightClick() // S2: 6 -> 4
            step(90)
            assertThat(ammo(shooter)).isEqualTo(4)
            shooter.moveTo(Vec3(0.0, groundY, 0.0))
            shooter.faces(victim)

            shooter.leftClick()
            step(20)

            assertThat(ammo(shooter)).`as`("the landed thrust gave one round back").isEqualTo(5)
        }
    }

    @Test
    fun `vendetta S2 spends two rounds, hops back, fires eight beams and stacks four tremor`() {
        persona("persona-vendetta-s2") {
            val (shooter, victim) = fighters(8.0)
            shooter.holds("vendetta-rifle")
            shooter.faces(victim)
            val ammoBefore = ammo(shooter)
            val hpBefore = hp(victim)

            shooter.rightClick()
            assertThat(ammo(shooter)).isEqualTo(ammoBefore - 2)
            step(1)
            val crossbows = sim.displays.spawned.count { it.spec.kind is DisplayKind.Item }
            assertThat(crossbows).`as`("the ghost rack unfolds one barrel per tick").isGreaterThanOrEqualTo(1)
            step(70)

            assertThat(sim.displays.spawned.count { it.spec.kind is DisplayKind.Item }).isEqualTo(8)
            assertThat(hits(victim)).`as`("eight beams plus the closing hit").isEqualTo(9)
            assertThat(hpBefore - hp(victim)).isBetween(9.0, 18.0)
            assertThat(stacks(victim, tremor)).isEqualTo(4)
            assertThat(shooter.velocity.z).`as`("recoil hop pushed backwards").isLessThan(0.0)
            assertThat(victim.frozenTicks).`as`("closing hit-stop").isGreaterThan(0)
            assertThat(glyphs()).`as`("a hit mark per beam").isGreaterThanOrEqualTo(8)
            assertThat(peak).isBetween(20, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `vendetta S3 telegraphs, lands three beats 22 ticks apart and eats the tremor`() {
        persona("persona-vendetta-s3") {
            val (shooter, victim) = fighters(5.0)
            shooter.holds("vendetta-rifle")
            shooter.faces(victim)
            val ammoBefore = ammo(shooter)
            val hpBefore = hp(victim)

            shooter.rightClick(sneaking = true)
            assertThat(ammo(shooter)).isEqualTo(ammoBefore - 3)
            assertThat(shooter.frozenTicks).`as`("planted for the telegraph").isGreaterThan(0)
            assertThat(victim.glowing).`as`("targets are outlined during the telegraph").isTrue()

            step(20)
            assertThat(hp(victim)).`as`("telegraph window deals nothing").isEqualTo(hpBefore)
            assertThat(sim.displays.spawned).`as`("summoning circle + rack are up").isNotEmpty()

            step(20) // t40: the eight volley beams have landed
            val afterVolley = hp(victim)
            assertThat(hits(victim)).isEqualTo(8)
            assertThat(hpBefore - afterVolley).isBetween(4.0, 8.0)
            assertThat(stacks(victim, tremor)).isEqualTo(2)

            step(10) // t50: the two spike hits
            val afterSpikes = hp(victim)
            assertThat(hits(victim)).isEqualTo(10)
            assertThat(afterVolley - afterSpikes).isGreaterThanOrEqualTo(3.5)
            assertThat(stacks(victim, tremor)).isEqualTo(4)
            assertThat(stacks(victim, bleed)).isGreaterThanOrEqualTo(1)

            step(30) // t80: the shotgun finale consumed every tremor stack
            assertThat(hits(victim)).`as`("8 beams + 2 spikes + the finale").isEqualTo(11)
            assertThat(stacks(victim, tremor)).isZero()
            assertThat(hpBefore - hp(victim)).`as`("three beats plus 1 per stack").isGreaterThanOrEqualTo(13.0)
            step(40)

            assertThat(peak).isBetween(30, 60)
            assertThat(glyphs()).`as`("cross flash + finale flash").isGreaterThanOrEqualTo(2)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `vendetta S3 is refused with fewer than three rounds`() {
        persona("persona-vendetta-s3-dry") {
            val (shooter, victim) = fighters(5.0)
            shooter.holds("vendetta-rifle")
            shooter.faces(victim)
            repeat(2) {
                shooter.rightClick() // S2: 6 -> 4 -> 2
                step(80)
            }
            assertThat(ammo(shooter)).isEqualTo(2)
            val hpBefore = hp(victim)

            shooter.rightClick(sneaking = true)
            step(40)

            assertThat(ammo(shooter)).`as`("no rounds spent on a refused cast").isEqualTo(2)
            assertThat(shooter.frozenTicks).`as`("no plant").isZero()
            assertThat(hp(victim)).isEqualTo(hpBefore)
        }
    }

    @Test
    fun `vendetta DEF slips through untouchable, ripostes the enemy and tags two tremor`() {
        persona("persona-vendetta-def") {
            val (shooter, victim) = fighters(2.5)
            shooter.holds("vendetta-rifle")
            shooter.faces(victim)
            val ammoBefore = ammo(shooter)
            val hpBefore = hp(victim)

            shooter.cast("vendetta-rifle-def")
            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(shooter.id, immunity)).`as`("untouchable while sliding").isTrue()
            step(30)

            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(shooter.id, immunity)).isFalse()
            assertThat(hpBefore - hp(victim)).isBetween(3.5, 7.0)
            assertThat(stacks(victim, tremor)).isEqualTo(2)
            assertThat(ammo(shooter)).isEqualTo(ammoBefore)
            assertThat(shooter.position.z).isGreaterThan(2.5)
            assertThat(peak).isBetween(10, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    // ------------------------------------------------------------------ ember-gauntlet

    @Test
    fun `ember S1 lunges for two knuckles, stacks burn and banks a charge`() {
        persona("persona-ember-s1") {
            val (brawler, victim) = fighters(2.0)
            brawler.holds("ember-gauntlet")
            brawler.faces(victim)
            val energyBefore = energy(brawler)
            val hpBefore = hp(victim)

            brawler.leftClick()
            assertThat(energy(brawler)).`as`("12 energy spent").isEqualTo(energyBefore - 12)
            assertThat(stacks(brawler, charge)).`as`("casting builds charge").isEqualTo(1)
            step(3)
            assertThat(stacks(victim, burn)).isGreaterThanOrEqualTo(1)
            assertThat(hits(victim)).`as`("knuckle 1 has landed").isEqualTo(1)
            step(5)
            assertThat(stacks(victim, burn)).`as`("one burn per knuckle (short DoT, expires after ~1s)").isEqualTo(2)
            step(35)

            assertThat(hits(victim)).`as`("two knuckles").isEqualTo(2)
            assertThat(hpBefore - hp(victim)).isGreaterThanOrEqualTo(4.5)
            assertThat(glyphs()).isGreaterThanOrEqualTo(2)
            assertThat(peak).isBetween(10, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `ember S2 fires three hoops, stacks burn, vents smoke and recoils`() {
        persona("persona-ember-s2")  {
            val (brawler, victim) = fighters(5.0)
            brawler.holds("ember-gauntlet")
            brawler.faces(victim)
            val hpBefore = hp(victim)

            brawler.rightClick()
            step(14)
            assertThat(stacks(victim, burn)).`as`("one burn per hoop").isEqualTo(3)
            step(36)

            assertThat(hpBefore - hp(victim)).isBetween(3.0, 14.0)
            assertThat(stacks(brawler, charge)).`as`("no overdrive without charge").isZero()
            assertThat(hits(victim)).`as`("one hit per hoop").isEqualTo(3)
            assertThat(blocksNamed("orange")).isGreaterThanOrEqualTo(3 * 10)
            assertThat(blocksNamed("black")).`as`("smoke puff").isGreaterThan(0)
            assertThat(brawler.velocity.z).`as`("recoil").isLessThan(0.0)
            assertThat(victim.frozenTicks).isGreaterThan(0)
            assertThat(peak).isBetween(40, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `ember S2 with three charge adds overdrive hoops and spends the charge`() {
        persona("persona-ember-s2-overdrive") {
            val (brawler, victim) = fighters(5.0)
            brawler.holds("ember-gauntlet")
            brawler.faces(victim)
            stack(brawler, charge, 4)
            assertThat(stacks(brawler, charge)).isEqualTo(4)

            brawler.rightClick()
            assertThat(stacks(brawler, charge)).`as`("overdrive spends 3").isEqualTo(1)
            step(16)
            assertThat(stacks(victim, burn)).`as`("3 hoops + 2 overdrive burn, capped at 5").isEqualTo(5)
            step(34)

            assertThat(blocksNamed("yellow")).isGreaterThanOrEqualTo(14)
            assertThat(peak).isBetween(40, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `ember S3 plants, raises the column, lands three beats and detonates burn and charge`() {
        persona("persona-ember-s3") {
            val (brawler, victim) = fighters(4.0)
            brawler.holds("ember-gauntlet")
            brawler.faces(victim)
            stack(brawler, charge, 4)
            val energyBefore = energy(brawler)
            val hpBefore = hp(victim)

            brawler.rightClick(sneaking = true)
            assertThat(energy(brawler)).`as`("70 energy spent").isEqualTo(energyBefore - 70)
            assertThat(brawler.frozenTicks).`as`("planted for the telegraph").isGreaterThan(0)
            assertThat(victim.glowing).isTrue()

            step(24)
            assertThat(hp(victim)).`as`("telegraph window deals nothing").isEqualTo(hpBefore)
            assertThat(blocksNamed("orange")).isGreaterThan(0)

            step(10) // t34: the three spiral hits
            val afterSpiral = hp(victim)
            assertThat(hits(victim)).isEqualTo(3)
            assertThat(hpBefore - afterSpiral).isGreaterThanOrEqualTo(2.5)
            assertThat(stacks(victim, burn)).isGreaterThanOrEqualTo(1)

            step(36) // t70: finale on this tick; burn and charge are consumed
            step(50)
            assertThat(hits(victim)).`as`("3 spiral + 2 halo + the finale").isEqualTo(6)
            assertThat(stacks(brawler, charge)).`as`("charge detonated").isZero()
            assertThat(stacks(victim, burn)).`as`("burn detonated").isZero()
            assertThat(hpBefore - hp(victim)).isGreaterThanOrEqualTo(13.0)
            assertThat(peak).isBetween(30, 60)
            assertThat(glyphs()).isGreaterThanOrEqualTo(2)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `ember DEF is untouchable for the slip, shoves and singes the front arc and banks two charge`() {
        persona("persona-ember-def") {
            val (brawler, victim) = fighters(2.0)
            brawler.holds("ember-gauntlet")
            brawler.faces(victim)
            val energyBefore = energy(brawler)
            val hpBefore = hp(victim)

            brawler.cast("ember-gauntlet-def")
            assertThat(energy(brawler)).`as`("15 energy spent").isEqualTo(energyBefore - 15)
            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(brawler.id, immunity)).isTrue()
            step(8)
            assertThat(stacks(victim, burn)).`as`("singed by the ring").isEqualTo(1)
            step(22)

            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(brawler.id, immunity)).isFalse()
            assertThat(stacks(brawler, charge)).isEqualTo(2)
            assertThat(hpBefore - hp(victim)).isGreaterThanOrEqualTo(2.5)
            assertThat(peak).isBetween(20, 60)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `the persona items resolve as shipped content`() {
        persona("persona-items") {
            val registry = sim.items.itemRegistry
            assertThat(registry.find(ItemId("vendetta-rifle"))).isNotNull
            assertThat(registry.find(ItemId("ember-gauntlet"))).isNotNull
        }
    }

    private companion object {
        /** Offset from [GameSim.DEFAULT_SEED] of a seed on which none of these scenarios rolls a crit. */
        const val SEED_OFFSET = 9L
    }
}
