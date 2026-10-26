package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.service.training.TrainingDummy
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.items.application.service.training.TrainingRackService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-11: the lobby training range - travel button, weapon rack, dummies, immune bystanders, lobby-only items. */
class LobbyTrainingScenarioTest {

    private val lobby = WorldId("lobby")

    /** The range plan; also "loads the lobby world" so the dummies may stand up. */
    private fun ScenarioScope.range(): ILobbyTrainingRange {
        sim.trainingShells.worldLoaded = true
        return sim.services.getOrThrow<ILobbyTrainingRange>()
    }

    private fun ScenarioScope.bodyId(spot: String) = checkNotNull(sim.trainingShells.bodyOf(spot)) { "no body for $spot" }.id

    private fun ScenarioScope.dummyHp(spot: String): Double =
        checkNotNull(sim.services.getOrThrow<IEntityDamageGateway>().hitPoints(bodyId(spot)))

    @Test
    fun `the Training button, the rack and a pistol shot - the dummy loses HP, a damage number shows and a bystander is untouched`() {
        scenario("lobby-training-pistol") {
            val plan = range().plan
            val shooter = player("shooter", at = Vec3(0.5, 65.0, 6.5))
            shooter.world = lobby
            val bystander = player("bystander", at = Vec3(plan.spawn.x + 4.0, plan.spawn.y, plan.spawn.z + 4.0))
            bystander.world = lobby

            // 1. the kit's "Training" button teleports to the range
            sim.services.getOrThrow<IGuiIntentRouter>()
                .dispatch(checkNotNull(sim.server.view(shooter.id)), GuiIntent("lobby.training.travel"))
            assertThat(shooter.position).isEqualTo(plan.spawn)
            assertThat(shooter.world).isEqualTo(lobby)
            assertThat(checkNotNull(sim.server.view(shooter.id)).messages.last()).contains("training range")

            // 2. the dummies stand up and the rack frames appear (both are spawned by their maintenance loops)
            advance(45)
            assertThat(sim.trainingShells.bodyOf("range-10")).isNotNull
            assertThat(dummyHp("range-10")).isEqualTo(TrainingDummy.MAX_HP)
            assertThat(sim.trainingShells.labels[bodyId("range-10")]!!.last()).contains("HP 500/500")

            // 3. the rack hands out a pistol (frames exist for every weapon; non-op, lobby-only)
            val rack = checkNotNull(sim.items.trainingRack)
            assertThat(sim.trainingRack.frames.map { it.itemId.value }).contains("pistol", "rifle", "knight-sword", "fire-staff")
            assertThat(rack.claim(shooter.id, ItemId("pistol")).isSuccess).isTrue()
            assertThat(sim.itemDelivery.trainingStacks[shooter.id]).containsExactly("pistol")
            sim.hold(shooter, "pistol") // the rack stack becomes the held item
            val ammoBefore = ammo(shooter)

            // 4. shoot the 10-block dummy
            val target = checkNotNull(sim.trainingShells.bodyOf("range-10"))
            shooter.faces(target)
            repeat(6) {
                shooter.rightClick()
                advance(8)
            }
            val hit = eventsOf<DamageDealt>().filter { it.target == target.id }
            assertThat(hit).describedAs("damage dealt to the dummy").isNotEmpty()
            assertThat(dummyHp("range-10")).isLessThan(TrainingDummy.MAX_HP)
            assertThat(ammo(shooter)).describedAs("ammo is spent as usual").isLessThan(ammoBefore)

            // 5. floating number + tag
            val numbers = sim.displays.spawned.filter { it.lines.singleOrNull()?.contains("-") == true }
            assertThat(numbers).isNotEmpty()
            assertThat(numbers.first().world).isEqualTo(lobby)
            assertThat(sim.trainingShells.labels[target.id]!!.last()).doesNotContain("HP 500/500")

            // 6. a player standing in the range takes nothing from another player
            val before = hp(bystander)
            shooter.faces(bystander)
            repeat(4) {
                shooter.rightClick()
                advance(8)
            }
            assertThat(eventsOf<DamageDealt>().none { it.target == bystander.id }).isTrue()
            assertThat(hp(bystander)).isEqualTo(before)
            assertThat(hp(shooter)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)

            // 7. idle for 3 s: the dummy refills and the DPS reads zero
            advance(80)
            assertThat(dummyHp("range-10")).isEqualTo(TrainingDummy.MAX_HP)
            assertThat(sim.trainingShells.labels[target.id]!!.last()).contains("HP 500/500").contains("DPS 0.0")
        }
    }

    @Test
    fun `outside the range players still take damage - the immunity is by region`() {
        scenario("lobby-training-region") {
            val shooter = player("shooter", at = Vec3(0.5, 65.0, 6.5))
            val victim = player("victim", at = Vec3(0.5, 65.0, 16.5))
            shooter.world = lobby
            victim.world = lobby
            shooter.holds("pistol")
            shooter.faces(victim)
            repeat(6) {
                shooter.rightClick()
                advance(8)
            }
            assertThat(eventsOf<DamageDealt>().filter { it.target == victim.id }).isNotEmpty()
            assertThat(hp(victim)).isLessThan(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
        }
    }

    @Test
    fun `the melee pit dummy takes sword hits and the moving target moves`() {
        scenario("lobby-training-melee") {
            val plan = range().plan
            val fighter = player("fighter", at = plan.spawn)
            fighter.world = lobby
            advance(10)

            val pit = plan.dummies.first { it.id == "melee-1" }
            fighter.moveTo(Vec3(pit.x - 1.2, pit.y, pit.z))
            checkNotNull(sim.items.trainingRack).claim(fighter.id, ItemId("knight-sword"))
            sim.hold(fighter, "knight-sword")
            fighter.faces(checkNotNull(sim.trainingShells.bodyOf("melee-1")))
            repeat(4) {
                fighter.leftClick()
                advance(12)
            }
            assertThat(dummyHp("melee-1")).isLessThan(TrainingDummy.MAX_HP)

            val mover = checkNotNull(sim.trainingShells.bodyOf("mover-1"))
            val start = mover.position
            advance(100)
            assertThat(mover.position).isNotEqualTo(start)
            assertThat(mover.position.z).isEqualTo(start.z)
        }
    }

    @Test
    fun `the staff bay has three RANGE dummies at 5, 8 and 11 blocks and a frost shard lowers their HP`() {
        scenario("lobby-training-staff-bay") {
            val plan = range().plan
            val staff = plan.dummies.filter { it.id.startsWith("staff-") }
            assertThat(staff.map { it.id }).containsExactlyInAnyOrder("staff-5", "staff-8", "staff-11")
            assertThat(staff.map { it.kind }.toSet()).containsExactly(com.astarworks.astera.core.domain.lobby.TrainingDummyKind.RANGE)
            advance(45) // the dummies stand up
            val caster = player("caster", at = plan.spawn)
            caster.world = lobby
            caster.holds("frost-staff")
            for (spot in staff) {
                assertThat(dummyHp(spot.id)).describedAs("${spot.id} stands at full HP").isEqualTo(TrainingDummy.MAX_HP)
                // the caster stands on the bay's own stand: `distance` blocks west of the target, same lane
                caster.moveTo(Vec3(spot.x - spot.distance!!, spot.y, spot.z))
                caster.faces(checkNotNull(sim.trainingShells.bodyOf(spot.id)))
                repeat(6) {
                    caster.rightClick()
                    advance(20)
                }
                assertThat(dummyHp(spot.id)).describedAs("${spot.id} lost HP to the frost shard").isLessThan(TrainingDummy.MAX_HP)
            }
        }
    }

    @Test
    fun `rack weapons are lobby-only - starting a match takes them away`() {
        scenario("lobby-training-lobby-only") {
            val plan = range().plan
            val red = player("red", at = plan.spawn)
            val blue = player("blue", at = plan.spawn)
            red.world = lobby
            blue.world = lobby
            val rack: TrainingRackService = checkNotNull(sim.items.trainingRack)
            assertThat(rack.claim(red.id, ItemId("rifle")).isSuccess).isTrue()
            assertThat(rack.claim(blue.id, ItemId("pistol")).isSuccess).isTrue()
            // a second claim of the same weapon is refused instead of stacking up
            assertThat(rack.claim(red.id, ItemId("rifle")).isFailure).isTrue()

            startMatch("annihilation", mapOf(red to "red"), stage = "annihilation-arena")
            advance(25) // the sweep runs once a second

            assertThat(sim.itemDelivery.trainingStacks[red.id]).isNull()
            assertThat(sim.itemDelivery.trainingStacks[blue.id]).containsExactly("pistol") // not a participant
        }
    }
}
