package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.config.toSpec
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.application.port.outbound.IOreNodeWorld
import com.astarworks.astera.match.domain.orenode.OreNodeSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Mid ore nodes (Annihilation style). At match start every node of the stage's `content/ore-nodes/` file is placed in the
 * match world. A pickaxe of at least the node's tier mines it: the drops go to the player's inventory, the block becomes
 * the placeholder for `regrow_seconds`, then the ore returns. A weaker tool (or a bare hand) gets an action-bar hint and
 * nothing happens. A node is never an ordinary cover block: the Paper listener asks [mine] first and cancels the vanilla break.
 */
public class OreNodeService(
    private val registry: IOreNodeRegistry,
    private val stages: IStageRegistry?,
    private val matchWorlds: IMatchWorldRepository,
    private val customBlocks: ICustomBlockRegistry,
    private val items: IItemRegistry,
    private val delivery: () -> IItemDelivery?,
    private val server: IMcServer,
    private val world: IOreNodeWorld?,
    private val scheduler: IScheduler,
    private val onInvalid: (stageId: String, reason: String) -> Unit = { _, _ -> },
) : IAreaBreakExclusion {
    /** What one mining attempt did. */
    public sealed interface Outcome {
        /** Not an ore node of a running match: the caller treats the block as it would any other. */
        public data object NotANode : Outcome

        /** The node is mined and regrowing; the block stays unbreakable. */
        public data object Regrowing : Outcome

        /** The tool is not a pickaxe of the required tier. */
        public data object TooWeak : Outcome

        public data class Mined(val dropped: List<Pair<String, Int>>) : Outcome
    }

    private data class Key(val world: String, val x: Int, val y: Int, val z: Int)
    private class Node(val matchId: MatchId, val world: WorldId, val pos: Vec3, val spec: OreNodeSpec, var regrowing: Boolean = false)

    private val nodes = ConcurrentHashMap<Key, Node>()

    public fun onStarted(event: MatchStarted) {
        val stage = stages?.find(event.stageId)
        val source = (stage?.templateId ?: event.stageId).value
        val origin = stage?.origin ?: Vec3(0.0, 0.0, 0.0)
        val config = registry.find(source) ?: return
        val specs = config.nodes.mapNotNull { group ->
            runCatching { group.toSpec { id -> customBlocks.find(BlockType(id))?.baseBukkitMaterial } }
                .onFailure { onInvalid(source, it.message ?: it.toString()) }
                .getOrNull()
        }
        place(event.matchId, specs, origin, PLACE_ATTEMPTS)
    }

    /** The match world may not exist yet at [MatchStarted]; retry a few times. */
    private fun place(matchId: MatchId, specs: List<OreNodeSpec>, origin: Vec3, attemptsLeft: Int) {
        val worldId = matchWorlds.findByMatch(matchId)?.worldId
        if (worldId == null) {
            if (attemptsLeft > 0) scheduler.runLaterTicks(ExecutionScope.Global, PLACE_RETRY_TICKS) { place(matchId, specs, origin, attemptsLeft - 1) }
            return
        }
        for (spec in specs) for (cell in spec.cells) {
            val pos = Vec3(cell.x + origin.x, cell.y, cell.z + origin.z)
            nodes[keyOf(worldId, pos)] = Node(matchId, worldId, pos, spec)
            world?.setBlock(worldId, pos, spec.material)
        }
    }

    public fun onEnded(event: MatchEnded) {
        nodes.entries.removeIf { it.value.matchId == event.matchId }
    }

    /** [player] swings [held] (null = bare hand) at the block [pos] of [worldId]. */
    public fun mine(player: PlayerId, worldId: WorldId, pos: Vec3, held: ItemId?): Outcome {
        val node = nodes[keyOf(worldId, pos)] ?: return Outcome.NotANode
        if (node.regrowing) return Outcome.Regrowing
        val tool = held?.let { HitCustomBlockUseCase.toolOf(items, it) }
        val target = server.findPlayer(player)
        if (tool == null || tool.kind != ToolKind.PICKAXE || tool.tier.ordinal < node.spec.requiredTier.ordinal) {
            target?.postActionBar(NEEDS_TIER, mapOf("tier" to node.spec.requiredTier.name.lowercase()))
            return Outcome.TooWeak
        }
        node.regrowing = true
        val given = node.spec.drops.map { it.itemId to it.count }
        val deliver = delivery()
        for ((id, count) in given) {
            val spec = items.find(ItemId(id)) ?: continue
            if (target != null && deliver != null) deliver.give(target, spec, count)
        }
        world?.setBlock(node.world, node.pos, node.spec.placeholder)
        scheduler.runLaterTicks(ExecutionScope.Global, node.spec.regrowSeconds * TICKS_PER_SECOND) {
            if (nodes[keyOf(node.world, node.pos)] === node) {
                node.regrowing = false
                world?.setBlock(node.world, node.pos, node.spec.material)
            }
        }
        return Outcome.Mined(given)
    }

    /** A node cell (mined or regrowing) is never removed by an area skill: nodes are mined one at a time. */
    override fun isExcluded(world: WorldId, pos: Vec3): Boolean = nodes.containsKey(keyOf(world, pos))

    private fun keyOf(world: WorldId, pos: Vec3) = Key(world.value, floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())

    private companion object {
        val NEEDS_TIER = MessageKey("astera.ore.needs-tier")
        const val TICKS_PER_SECOND = 20L
        const val PLACE_ATTEMPTS = 10
        const val PLACE_RETRY_TICKS = 20L
    }
}
