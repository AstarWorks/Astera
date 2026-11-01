package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder 2026-11-01: "mining iron ore does nothing". Mining an Annihilation ore node must be readable: the ore enters the inventory, the
 * miner hears the `astera:ore/mined` cue, debris bursts at the block and an action-bar line names the haul. A phase-locked node gives nothing.
 */
class OreBreakFeedbackScenarioTest {

    /** A long build phase keeps the phase-2 gold locked while the scenario mines the phase-1 iron. */
    private val config = AnnihilationConfig(
        pillarHp = 1000,
        buildPhaseTicks = 4000,
        engagePhaseTicks = 200,
        centerPhaseTicks = 200,
        escalationPhaseTicks = 200,
        suddenDeathTicks = 400,
        bleedIntervalTicks = 20,
        bleedPercent = 10,
        bleedFloorPercent = 10,
    )

    @Test
    fun `mining an open node gives the ore with a sound, a debris burst and an action-bar line, and a phase-locked node gives nothing`() {
        scenario("ore-break-feedback", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val view = checkNotNull(sim.server.view(red.id))
            val pick = ItemId("diamond-pickaxe")
            fun cell(x: Int, y: Int, z: Int) = Vec3(origin.x + x, y.toDouble(), origin.z + z)
            val iron = cell(19, 61, -28)
            val gold = cell(17, 56, 21) // phase 2: closed at the start
            val stars = mutableListOf<StarsCollected>()
            sim.services.getOrThrow<IDomainEventBus>().subscribe(StarsCollected::class) { stars += it }
            val cue = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(SoundCues.ORE_MINED)) { "astera:ore/mined is not defined in content/sounds" }
            val oreSound = SoundSpec(cue.resourceKey, cue.defaultVolume, cue.defaultPitch)
            fun oreSounds() = sim.playerSounds.calls.count { it.playerId == red.id && it.sound == oreSound }
            advance(25)

            // Phase-locked node: nothing given, no cue, no burst, no "+n ore" line.
            assertThat(nodes.mine(red.id, worldId, gold, pick)).isInstanceOf(OreNodeService.Outcome.PhaseLocked::class.java)
            assertThat(view.givenItems).doesNotContain("ore-gold")
            assertThat(oreSounds()).isZero()
            assertThat(sim.oreNodes.bursts).isEmpty()
            assertThat(view.actionBars.map { it.value }).noneMatch { it.startsWith("astera.ore.mined.") }

            // Open node: swing until it breaks.
            var outcome: OreNodeService.Outcome = OreNodeService.Outcome.Cooldown
            var swings = 0
            while (outcome !is OreNodeService.Outcome.Mined && swings++ < 30) {
                advance(7)
                outcome = nodes.mine(red.id, worldId, iron, pick)
            }
            assertThat(outcome).isEqualTo(OreNodeService.Outcome.Mined(listOf("ore-iron" to 3)))
            assertThat(view.givenItems).contains("ore-iron")
            assertThat(oreSounds()).`as`("one pickup cue per break").isEqualTo(1)
            assertThat(sim.oreNodes.bursts).hasSize(1)
            assertThat(sim.oreNodes.bursts.single().take(3)).isEqualTo(listOf(iron.x.toInt(), 61, iron.z.toInt()))
            assertThat(view.actionBars.map { it.value }).contains("astera.ore.mined.ore-iron")
            // The node shows its depleted state (placeholder) until it regrows.
            assertThat(sim.oreNodes.materialAt(iron.x.toInt(), 61, iron.z.toInt())).isNotEqualTo(sim.oreNodes.bursts.single()[3])
            // The feedback grants no star of its own (the Paper listener asks StarBudget once per break, MINING source).
            assertThat(stars.size).isLessThanOrEqualTo(1)
        }
    }
}
