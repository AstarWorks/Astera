package com.astarworks.astera.gamesim

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IOreNodeStrikes
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.match.application.config.toSpec
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Founder 2026-11-01: "dug and nothing happened". A swing or tool skill refused by an ore node (tool tier too low, tool class that cannot hurt it)
 * must say why: the action-bar line, the `astera:ore/refused` cue and a deny puff at the node, and deal no damage. A skill that touches many
 * refused nodes in one cast answers once.
 */
class OreRefusalFeedbackScenarioTest {

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
    fun `hand and skill refusals (tier, wrong tool) show the line, the cue and a puff without damage, and one cast over many nodes answers once`() {
        scenario("ore-refusal-feedback", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val oreFile = checkNotNull(sim.services.getOrThrow<IOreNodeRegistry>().find("annihilation-arena")) { "no content/ore-nodes/annihilation-arena.yaml" }
            val customBlocks = sim.services.getOrThrow<ICustomBlockRegistry>()
            // Phase-1, level-1 starter ore: only the tool gate can refuse it.
            val group = oreFile.nodes.first { it.block == "astera:ore_iron_node" }
            val spec = group.toSpec(oreFile) { customBlocks.find(BlockType(it)) }
            val cells = spec.cells.map { Vec3(it.x + origin.x, it.y, it.z + origin.z) }
            check(cells.size >= 3) { "need 3 iron cells" }
            val nodes = sim.services.getOrThrow<OreNodeService>()
            val view = checkNotNull(sim.server.view(red.id))
            val cue = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(SoundCues.ORE_REFUSED)) { "astera:ore/refused is not defined in content/sounds" }
            val refusedSound = SoundSpec(cue.resourceKey, cue.defaultVolume, cue.defaultPitch)
            fun cues() = sim.playerSounds.calls.count { it.playerId == red.id && it.sound == refusedSound }
            fun bars(key: String) = view.actionBars.count { it.value == key }
            fun puffs() = sim.oreNodes.refusals.size
            fun cracked(c: Vec3) = sim.oreNodes.crackAt(c.x.toInt(), c.y.toInt(), c.z.toInt())
            val needsTier = OreNodeService.NEEDS_TIER.value
            val wrongTool = OreNodeService.WRONG_TOOL.value
            advance(25)

            // Hand, tier too low (bare hand): line + cue + puff, no damage.
            assertThat(nodes.mine(red.id, worldId, cells[0], null)).isEqualTo(OreNodeService.Outcome.TooWeak)
            assertThat(bars(needsTier)).isEqualTo(1)
            assertThat(cues()).isEqualTo(1)
            assertThat(puffs()).isEqualTo(1)
            assertThat(sim.oreNodes.refusals.last()).isEqualTo(Triple(cells[0].x.toInt(), cells[0].y.toInt(), cells[0].z.toInt()))
            assertThat(cracked(cells[0])).isNull()

            // Skill, tier too low: same feedback.
            advance(20)
            val wood = ToolType(ToolKind.PICKAXE, ToolTier.WOOD)
            assertThat(nodes.strike(red.id, worldId, cells[0], wood, 100)).isEqualTo(IOreNodeStrikes.Result.NONE)
            assertThat(bars(needsTier)).isEqualTo(2)
            assertThat(cues()).isEqualTo(2)
            assertThat(puffs()).isEqualTo(2)
            assertThat(cracked(cells[0])).isNull()

            // Wrong tool: shipped ore blocks give every tool class a (weak) entry, so derive a node the pickaxe cannot hurt from the real
            // iron spec (axe-only affinity) and run it through a service wired like the real one.
            val axeOnly = spec.copy(hitDamage = spec.hitDamage.filterKeys { it.kind == ToolKind.AXE })
            val wrongCell = cells[1]
            val map = ConcurrentHashMap<OreNodeService.Key, OreNodeService.Node>()
            map[OreNodeService.Key(worldId.value, wrongCell.x.toInt(), wrongCell.y.toInt(), wrongCell.z.toInt())] =
                OreNodeService.Node(matchId, worldId, wrongCell, axeOnly)
            val variant = OreNodeService(
                registry = sim.services.getOrThrow<IOreNodeRegistry>(),
                stages = null,
                matchWorlds = sim.services.getOrThrow<IMatchWorldRepository>(),
                customBlocks = customBlocks,
                items = sim.services.getOrThrow<IItemRegistry>(),
                delivery = { sim.services.get(IItemDelivery::class) },
                server = sim.services.getOrThrow<IMcServer>(),
                world = sim.oreNodes,
                scheduler = sim.scheduler,
                sounds = { sim.services.get(ISoundCuePlayer::class) },
                nodes = map,
            )

            // Hand, wrong tool (stone pickaxe on an axe-only node).
            advance(20)
            assertThat(variant.mine(red.id, worldId, wrongCell, ItemId("stone-pickaxe"))).isEqualTo(OreNodeService.Outcome.WrongTool)
            assertThat(bars(wrongTool)).isEqualTo(1)
            assertThat(cues()).isEqualTo(3)
            assertThat(puffs()).isEqualTo(3)
            assertThat(cracked(wrongCell)).isNull()

            // Skill, wrong tool.
            advance(20)
            val stone = ToolType(ToolKind.PICKAXE, ToolTier.STONE)
            assertThat(variant.strike(red.id, worldId, wrongCell, stone, 100)).isEqualTo(IOreNodeStrikes.Result.NONE)
            assertThat(bars(wrongTool)).isEqualTo(2)
            assertThat(cues()).isEqualTo(4)
            assertThat(puffs()).isEqualTo(4)
            assertThat(cracked(wrongCell)).isNull()

            // One cast (same tick) touching three refused nodes: one line, one cue, one puff; the next cast answers again.
            advance(20)
            val barsBefore = bars(needsTier)
            val cuesBefore = cues()
            val puffsBefore = puffs()
            for (c in cells.take(3)) nodes.strike(red.id, worldId, c, wood, 100)
            assertThat(bars(needsTier) - barsBefore).`as`("one message per cast").isEqualTo(1)
            assertThat(cues() - cuesBefore).`as`("one cue per cast").isEqualTo(1)
            assertThat(puffs() - puffsBefore).`as`("one puff per cast").isEqualTo(1)
            for (c in cells.take(3)) assertThat(cracked(c)).isNull()
            advance(20)
            for (c in cells.take(3)) nodes.strike(red.id, worldId, c, wood, 100)
            assertThat(bars(needsTier) - barsBefore).isEqualTo(2)
            assertThat(cues() - cuesBefore).isEqualTo(2)
        }
    }
}
