package com.astarworks.astera.gamesim

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.match.application.config.toSpec
import com.astarworks.astera.match.application.port.inbound.IOreNodeAccess
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.application.port.outbound.MatchPhaseSnapshot
import com.astarworks.astera.match.application.service.OreNodeService
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.orenode.OreAccess
import com.astarworks.astera.match.domain.orenode.OreNodeAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * One ore-node gate query for the server and the bot planner ([IOreNodeAccess] / [OreNodeAccess]). For every shipped node of
 * `annihilation-arena`, phases 1-5, Norma levels 1-5 and six tools (bare hand, stone / iron / diamond pickaxe, iron axe, iron shovel) the query's verdict
 * equals what [OreNodeService] does to a hand hit: it lands, or it is refused for the same reason with the same unlock.
 */
class OreNodeAccessQueryScenarioTest {

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

    private class FixedPhase(private val phase: NexusPhase) : IMatchPhaseClock {
        override fun current(matchId: MatchId) = MatchPhaseSnapshot(phase, 100L, 100L)
        override fun ticksUntil(matchId: MatchId, target: NexusPhase): Long = maxOf(0, target.number - phase.number) * 100L
    }

    @Test
    fun `the query agrees with enforcement for every shipped node over phases 1-5 and levels 1-5, and answers the concrete gold cases`() {
        scenario("ore-node-access-query", config) {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val yellow = player("yellow-1", team = "yellow")
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue", yellow to "yellow"), stage = "annihilation-arena")
            val worldId = checkNotNull(sim.services.getOrThrow<IMatchWorldRepository>().findByMatch(matchId)).worldId
            val origin = sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena"))?.origin ?: Vec3(0.0, 0.0, 0.0)
            val registry = sim.services.getOrThrow<IOreNodeRegistry>()
            val file = checkNotNull(registry.find("annihilation-arena"))
            val customBlocks = sim.services.getOrThrow<ICustomBlockRegistry>()
            val items = sim.services.getOrThrow<IItemRegistry>()
            val access = sim.services.getOrThrow<IOreNodeAccess>()
            val tools: List<ItemId?> = listOf(null, ItemId("stone-pickaxe"), ItemId("iron-pickaxe"), ItemId("diamond-pickaxe"), ItemId("iron-axe"), ItemId("iron-shovel"))
            fun toolOf(id: ItemId?): ToolType? = id?.let { HitCustomBlockUseCase.toolOf(items, it) }

            val properTools = com.astarworks.astera.core.application.skillengine.compose.blockProperTools(java.nio.file.Paths.get(System.getProperty("astera.sim.contentDir")))

            fun serviceAt(phase: NexusPhase, level: Int, map: ConcurrentHashMap<OreNodeService.Key, OreNodeService.Node>) = OreNodeService(
                registry = registry,
                properToolOf = properTools::get,
                stages = null,
                matchWorlds = sim.services.getOrThrow<IMatchWorldRepository>(),
                customBlocks = customBlocks,
                items = items,
                delivery = { sim.services.get(IItemDelivery::class) },
                server = sim.services.getOrThrow<IMcServer>(),
                world = sim.oreNodes,
                scheduler = sim.scheduler,
                levelOf = { level },
                phases = { FixedPhase(phase) },
                nodes = map,
            )

            fun verdictOf(outcome: OreNodeService.Outcome): OreAccess = when (outcome) {
                is OreNodeService.Outcome.PhaseLocked -> OreAccess.Locked(OreAccess.Reason.NEEDS_PHASE, OreAccess.Unlock(phase = outcome.required))
                is OreNodeService.Outcome.LevelLocked -> OreAccess.Locked(OreAccess.Reason.NEEDS_LEVEL, OreAccess.Unlock(level = outcome.required))
                OreNodeService.Outcome.WrongTool -> OreAccess.Locked(OreAccess.Reason.WRONG_TOOL, OreAccess.Unlock())
                OreNodeService.Outcome.TooWeak -> OreAccess.Locked(OreAccess.Reason.NEEDS_TIER, OreAccess.Unlock())
                is OreNodeService.Outcome.Hit, is OreNodeService.Outcome.Mined, OreNodeService.Outcome.Cooldown -> OreAccess.Allowed
                else -> error("unexpected outcome $outcome")
            }
            // TooWeak carries no tier in the outcome: compare the tier against the node's required tier separately.
            fun same(query: OreAccess?, enforced: OreAccess): Boolean = when {
                query is OreAccess.Locked && enforced is OreAccess.Locked && query.reason == OreAccess.Reason.NEEDS_TIER ->
                    enforced.reason == OreAccess.Reason.NEEDS_TIER
                else -> query == enforced
            }

            var compared = 0
            for (group in file.nodes) {
                val spec = group.toSpec(file, { customBlocks.find(BlockType(it)) }, properTools::get)
                val cell = spec.cells.first().let { Vec3(it.x + origin.x, it.y, it.z + origin.z) }
                val key = OreNodeService.Key(worldId.value, cell.x.toInt(), cell.y.toInt(), cell.z.toInt())
                for (phase in (1..5).map { checkNotNull(NexusPhase.byNumber(it)) }) for (level in 1..5) for (held in tools) {
                    val map = ConcurrentHashMap<OreNodeService.Key, OreNodeService.Node>()
                    map[key] = OreNodeService.Node(matchId, worldId, cell, spec)
                    val enforced = verdictOf(serviceAt(phase, level, map).mine(red.id, worldId, cell, held))
                    val query = access.check("annihilation-arena", group.block, phase.number, level, toolOf(held))
                    assertThat(same(query, enforced))
                        .`as`("${group.block} phase ${phase.number} level $level tool $held: query $query vs enforcement $enforced")
                        .isTrue()
                    assertThat(query).isEqualTo(OreNodeAccess.check(spec, phase.number, level, toolOf(held)))
                    compared++
                }
            }
            assertThat(compared).isEqualTo(file.nodes.size * 5 * 5 * tools.size)

            // Wrong tool: the shipped iron node is a pickaxe node with no entry for other classes, so a stone axe is refused as WRONG_TOOL.
            val iron = file.nodes.first { it.block == "astera:ore_iron_node" }.toSpec(file, { customBlocks.find(BlockType(it)) }, properTools::get)
            val wrongCell = iron.cells.first().let { Vec3(it.x + origin.x, it.y, it.z + origin.z) }
            val wrongMap = ConcurrentHashMap<OreNodeService.Key, OreNodeService.Node>()
            wrongMap[OreNodeService.Key(worldId.value, wrongCell.x.toInt(), wrongCell.y.toInt(), wrongCell.z.toInt())] =
                OreNodeService.Node(matchId, worldId, wrongCell, iron)
            val wrongOutcome = serviceAt(NexusPhase.Phase1Build, 1, wrongMap).mine(red.id, worldId, wrongCell, ItemId("stone-axe"))
            assertThat(wrongOutcome).isEqualTo(OreNodeService.Outcome.WrongTool)
            assertThat(OreNodeAccess.check(iron, 1, 1, ToolType(ToolKind.AXE, ToolTier.STONE)))
                .isEqualTo(OreAccess.Locked(OreAccess.Reason.WRONG_TOOL, OreAccess.Unlock()))

            // Concrete gold cases (opens in phase 2, needs level 2 and an iron tool).
            val gold = "astera:ore_gold_node"
            val ironPick = ToolType(ToolKind.PICKAXE, ToolTier.IRON)
            val stonePick = ToolType(ToolKind.PICKAXE, ToolTier.STONE)
            assertThat(access.check("annihilation-arena", gold, 1, 5, ironPick))
                .isEqualTo(OreAccess.Locked(OreAccess.Reason.NEEDS_PHASE, OreAccess.Unlock(phase = 2)))
            assertThat(access.check("annihilation-arena", gold, 2, 1, ironPick))
                .isEqualTo(OreAccess.Locked(OreAccess.Reason.NEEDS_LEVEL, OreAccess.Unlock(level = 2)))
            assertThat(access.check("annihilation-arena", gold, 2, 2, stonePick))
                .isEqualTo(OreAccess.Locked(OreAccess.Reason.NEEDS_TIER, OreAccess.Unlock(tier = ToolTier.IRON)))
            assertThat(access.check("annihilation-arena", gold, 2, 2, ironPick)).isEqualTo(OreAccess.Allowed)
            // Emerald opens in phase 3, needs level 4 and a diamond tool.
            assertThat(access.check("annihilation-arena", "astera:ore_emerald_node", 2, 5, ToolType(ToolKind.PICKAXE, ToolTier.DIAMOND)))
                .isEqualTo(OreAccess.Locked(OreAccess.Reason.NEEDS_PHASE, OreAccess.Unlock(phase = 3)))
            assertThat(access.check("annihilation-arena", "astera:ore_emerald_node", 3, 4, ToolType(ToolKind.PICKAXE, ToolTier.DIAMOND)))
                .isEqualTo(OreAccess.Allowed)
            // Starter ore is open from phase 1 to a stone tool at level 1; an unknown block or stage has no answer.
            assertThat(access.check("annihilation-arena", "astera:ore_iron_node", 1, 1, stonePick)).isEqualTo(OreAccess.Allowed)
            assertThat(access.check("annihilation-arena", "astera:no_such_node", 1, 1, stonePick)).isNull()
            assertThat(access.check("no-such-stage", gold, 1, 1, stonePick)).isNull()
        }
    }
}
