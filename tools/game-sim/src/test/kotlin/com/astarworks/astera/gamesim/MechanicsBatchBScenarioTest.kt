package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.world.BlockType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** WP5 (mechanics batch B) end to end through the real dispatcher, wiring and sim ports. */
class MechanicsBatchBScenarioTest {

    private fun ScenarioScope.run(caster: SimEntity, at: Vec3, target: SimEntity?, vararg mechanics: Mechanic) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            mechanics.toList(),
            MechanicContext(actorId = caster.id, targetId = target?.id, origin = at, worldId = caster.world),
        )
    }

    @Test
    fun `explosion damages enemies and mobs in the radius with falloff and spares allies and bystanders`() {
        scenario("mechanics-explode") {
            val caster = player("caster", team = "red")
            val mate = player("mate", at = Vec3(1.0, groundY, 7.0), team = "red")
            val near = player("near", at = Vec3(0.0, groundY, 8.0), team = "blue")
            val edge = player("edge", at = Vec3(0.0, groundY, 10.0), team = "blue")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 5.0), team = "blue")
            val far = player("far", at = Vec3(0.0, groundY, 40.0), team = "blue")
            val center = Vec3(0.0, groundY, 6.0)

            run(
                caster, center, null,
                Mechanic.Explode(
                    Targeter.Origin,
                    power = NumericValue.Literal(3.0),
                    radius = NumericValue.Literal(5.0),
                    components = listOf(DamageComponent(NumericValue.Literal(10.0), DamageAttribute.PHYSICAL)),
                ),
            )

            assertThat(hp(near)).isLessThan(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            assertThat(hp(dummy)).isLessThan(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            assertThat(hp(far)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            assertThat(hp(caster)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            // Friendly fire policy of the real damage pipeline applies to explosions too.
            assertThat(hp(mate)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            // Linear falloff: the target at the centre's doorstep takes more than the one at the edge.
            assertThat(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP - hp(dummy)).isGreaterThan(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP - hp(near))
            assertThat(hp(edge)).isGreaterThanOrEqualTo(hp(near))
            // Knocked away from the blast.
            assertThat(near.velocity.z).isGreaterThan(0.0)
            assertThat(dummy.velocity.z).isLessThan(0.0)

            val explosion = sim.recorder.effects("explosion").single()
            assertThat(explosion.detail).contains("radius=5.0").contains("blocks=None")
        }
    }

    @Test
    fun `set_block reverts, is refused outside an arena, and remove_block restores the original`() {
        scenario("mechanics-blocks") {
            val caster = player("caster", team = "red")
            val outsider = player("outsider") // not in a match: no arena
            val cell = Vec3(4.0, groundY + 1, 4.0)
            val above = Vec3(4.0, groundY + 2, 4.0)
            sim.world.placeBlock(caster.world, above, BlockType("minecraft:dirt"))

            run(caster, cell, null, Mechanic.SetBlock(Targeter.Origin, "minecraft:ice", revertAfterTicks = IntValue.Literal(40)))
            assertThat(sim.world.blockAt(caster.world, cell)).isEqualTo(BlockType("minecraft:ice"))

            run(caster, above, null, Mechanic.RemoveBlock(Targeter.Origin, revertAfterTicks = IntValue.Literal(40)))
            assertThat(sim.world.blockAt(caster.world, above)).isNull()

            advance(39)
            assertThat(sim.world.blockAt(caster.world, cell)).isEqualTo(BlockType("minecraft:ice"))
            advance(1)
            assertThat(sim.world.blockAt(caster.world, cell)).isNull()
            assertThat(sim.world.blockAt(caster.world, above)).isEqualTo(BlockType("minecraft:dirt"))

            // Outside any arena nothing happens, unless the skill opts out explicitly.
            run(outsider, cell, null, Mechanic.SetBlock(Targeter.Origin, "minecraft:ice"))
            assertThat(sim.world.blockAt(caster.world, cell)).isNull()
            run(
                outsider, cell, null,
                Mechanic.SetBlock(Targeter.Origin, "minecraft:ice", outsideArena = BoolValue.Literal(true)),
            )
            assertThat(sim.world.blockAt(caster.world, cell)).isEqualTo(BlockType("minecraft:ice"))

            assertThat(sim.recorder.effects("block_place")).isNotEmpty()
            assertThat(sim.recorder.effects("block_remove")).isNotEmpty()
        }
    }

    @Test
    fun `summons spawn on the owner's side, never outlive their lifetime, and displays expire`() {
        scenario("mechanics-summon-display") {
            val caster = player("caster", team = "red")
            val before = sim.world.entities.size

            run(
                caster, caster.position, null,
                Mechanic.Summon(Targeter.Caster, "ZOMBIE", count = IntValue.Literal(2), lifetimeTicks = IntValue.Literal(60)),
                Mechanic.SpawnDisplay(
                    Targeter.Caster,
                    DisplaySpec(DisplayKind.Block("minecraft:glass"), lifetimeTicks = 30L),
                ),
            )

            val summons = sim.world.entities.filter { it.kind == SimEntityKind.MOB }
            assertThat(summons).hasSize(2)
            assertThat(summons.map { it.team?.value }).containsOnly("red")
            assertThat(sim.displays.active).hasSize(1)

            advance(30)
            assertThat(sim.displays.active).isEmpty()
            assertThat(sim.world.entities).hasSize(before + 2)

            advance(30)
            assertThat(sim.world.entities).hasSize(before)
            assertThat(sim.recorder.effects("summon")).hasSize(2)
            assertThat(sim.recorder.effects("despawn")).hasSize(2)
            assertThat(sim.recorder.effects("display_remove")).hasSize(1)
        }
    }

    @Test
    fun `heal glow shake and lightning show up in the trace`() {
        scenario("mechanics-heal-glow-shake-lightning") {
            val caster = player("caster", team = "red")
            val ally = player("ally", at = Vec3(0.0, groundY, 4.0), team = "red")
            sim.setMaxHealth(ally, 40.0)
            sim.services.getOrThrow<IPlayerHealthGateway>().damage(ally.id, 25.0, HealthChangeSource.Other("test"))
            assertThat(hp(ally)).isEqualTo(15.0)

            run(
                caster, caster.position, ally,
                Mechanic.Heal(Targeter.Target, NumericValue.Literal(10.0)),
                Mechanic.Glow(Targeter.Target, IntValue.Literal(20)),
                Mechanic.CameraShake(Targeter.Target, NumericValue.Literal(0.8), IntValue.Literal(6)),
                Mechanic.SummonLightning(Targeter.Target),
            )

            assertThat(hp(ally)).isEqualTo(25.0)
            assertThat(ally.glowing).isTrue()
            assertThat(sim.recorder.effects("lightning")).hasSize(1)
            assertThat(sim.recorder.effects("lightning").single().detail).contains("damaging=false")

            advance(8)
            assertThat(sim.feedback.kicks.filter { it.target == ally.id }).hasSize(4)
            advance(12)
            assertThat(ally.glowing).isFalse()
            assertThat(sim.recorder.effects("glow").map { it.detail }).containsExactly("ally on", "ally off")
        }
    }

    @Test
    fun `the demo skill decodes from YAML and runs every batch B mechanic end to end`() {
        scenario("mechanics-demo-quake") {
            val caster = player("caster", team = "red")
            val enemy = player("enemy", at = Vec3(0.0, groundY, 4.0), team = "blue")
            sim.setMaxHealth(caster, 40.0)
            sim.services.getOrThrow<IPlayerHealthGateway>().setHealth(caster.id, 10.0, HealthChangeSource.Other("test"))

            caster.cast("mechanics-demo-quake")
            advance(2)

            assertThat(hp(enemy)).isLessThan(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            assertThat(hp(caster)).isEqualTo(30.0)
            assertThat(enemy.glowing).isTrue()
            assertThat(sim.feedback.kicks.filter { it.target == enemy.id }).isNotEmpty()
            assertThat(sim.recorder.effects("explosion")).hasSize(1)
            assertThat(sim.recorder.effects("lightning")).hasSize(1)
            assertThat(sim.recorder.effects("block_place")).hasSize(1)
            assertThat(sim.displays.active).hasSize(1)
            // The per-target visuals are anchored on the enemy (z=4), not at the caster (z=0).
            for (kind in listOf("explosion", "lightning", "block_place")) {
                assertThat(sim.recorder.effects(kind).single().at!!.z).describedAs(kind).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1.0))
            }

            advance(80)
            assertThat(enemy.glowing).isFalse()
            assertThat(sim.displays.active).isEmpty()
            assertThat(sim.recorder.effects("block_remove")).hasSize(1) // the revert
        }
    }

    @Test
    fun `the cut-in demo skill flashes white then gold then speed lines for the caster only`() {
        scenario("vfx-demo-cutin") {
            val caster = player("caster", team = "red")
            val enemy = player("enemy", at = Vec3(0.0, groundY, 4.0), team = "blue")

            caster.cast("vfx-demo-cutin")
            advance(6)

            val flashes = sim.feedback.flashes
            assertThat(flashes.map { it.overlay }).containsExactly(
                com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay.WHITE,
                com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay.GOLD_BURST,
                com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay.SPEED_DIAG,
            )
            assertThat(flashes.map { it.tick }).isSorted()
            assertThat(flashes.map { it.target }.toSet()).containsExactly(caster.id)
            assertThat(flashes.none { it.target == enemy.id }).isTrue()
            assertThat(flashes[1].colorRgb).isEqualTo(0xFFC84A)
            assertThat(sim.recorder.effects("screen_flash")).hasSize(3)
        }
    }
}
