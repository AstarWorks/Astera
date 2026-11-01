package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IOreNodeStrikes
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.config.toSpec
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.application.port.outbound.IOreNodeWorld
import com.astarworks.astera.match.domain.orenode.OreAccess
import com.astarworks.astera.match.domain.orenode.OreNodeAccess
import com.astarworks.astera.match.domain.orenode.OreNodeRules
import com.astarworks.astera.match.domain.orenode.OreNodeSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Mid ore nodes (Annihilation style). At match start every node of the stage's `content/ore-nodes/` file is placed in the
 * match world. A node has HP and a tool affinity (the block's `tool_effectiveness`): the matching tool class deals big hits, the
 * others small ones. A tool of at least the node's `required_tier` hits it, a player under its `min_norma_level` deals nothing
 * (deny sound + action bar with the required level), and a higher Norma level speeds the hit up (`level_speed`). The hit that takes
 * the HP to 0 mines it: the drops go to the player's inventory, the block becomes the placeholder for `regrow_seconds`, then
 * the ore returns. Hand mining ([mine]) and tool skills ([strike], `break_area`) share [hit], so both obey the same rules.
 * A node is never an ordinary cover block: the Paper listener asks [mine] first and cancels the vanilla break.
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
    /** The player's Norma level, or null outside a Norma match (then no level gate applies and the level-1 multiplier is used). */
    private val levelOf: (PlayerId) -> Int? = { null },
    private val onInvalid: (stageId: String, reason: String) -> Unit = { _, _ -> },
    /** Phase clock of phase-driven matches (Annihilation): a node whose `phase_gates` entry is later than the match's phase takes no damage. Null = no phase gate. */
    private val phases: () -> IMatchPhaseClock? = { null },
    /** Plays the `astera:ore/mined` and `astera:ore/refused` cues to the player. Null = silent (no sound system). */
    private val sounds: () -> ISoundCuePlayer? = { null },
    /** Carried across a Match rewire: which nodes are mined or regrowing, and each player's last hit. */
    private val lastHit: ConcurrentHashMap<PlayerId, Long> = ConcurrentHashMap(),
    private val nodes: ConcurrentHashMap<Key, Node> = ConcurrentHashMap(),
) : IAreaBreakExclusion, IOreNodeStrikes {
    /** Tick of each player's last skill refusal feedback ([refuse] throttle). */
    private val refusedAt = ConcurrentHashMap<PlayerId, Long>()

    /** What one mining attempt did. */
    public sealed interface Outcome {
        /** Not an ore node of a running match: the caller treats the block as it would any other. */
        public data object NotANode : Outcome

        /** The node is mined and regrowing; the block stays unbreakable. */
        public data object Regrowing : Outcome

        /** No tool, or a tool below the node's required tier. */
        public data object TooWeak : Outcome

        /** The tool class cannot hurt this node at all. */
        public data object WrongTool : Outcome

        /** The match is still before the phase the node opens in (`phase_gates`); [required] is that phase's number. */
        public data class PhaseLocked(val required: Int) : Outcome

        /** The player's Norma level is under the node's `min_norma_level`. */
        public data class LevelLocked(val required: Int) : Outcome

        /** A hit landed inside the node's hit cooldown and is ignored. */
        public data object Cooldown : Outcome

        /** The hit cracked the node but did not finish it: [hp] of [maxHp] is left. */
        public data class Hit(val hp: Int, val maxHp: Int) : Outcome

        public data class Mined(val dropped: List<Pair<String, Int>>) : Outcome
    }

    public data class Key(val world: String, val x: Int, val y: Int, val z: Int)
    public class Node(
        val matchId: MatchId,
        val world: WorldId,
        val pos: Vec3,
        val spec: OreNodeSpec,
        var regrowing: Boolean = false,
        var hp: Int = spec.maxHp,
    )



    public fun onStarted(event: MatchStarted) {
        val stage = stages?.find(event.stageId)
        val source = (stage?.templateId ?: event.stageId).value
        val origin = stage?.origin ?: Vec3(0.0, 0.0, 0.0)
        val config = registry.find(source) ?: return
        val specs = config.nodes.mapNotNull { group ->
            runCatching { group.toSpec(config) { id -> customBlocks.find(BlockType(id)) } }
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
        gate(player, node, tool, throttled = false)?.let { return it }
        val now = scheduler.currentTick()
        // Only a landed hit restarts the cooldown: a click inside it must not push the next hit further away.
        val last = lastHit[player]
        if (last != null && now - last < HIT_COOLDOWN_TICKS) return Outcome.Cooldown
        lastHit[player] = now
        return hit(node, player, OreNodeRules.damage(node.spec, tool!!.kind, tool.tier, levelOf(player) ?: 1))
    }

    /**
     * Asks [OreNodeAccess] (the one gate shared with the bot planner) and, on Locked, answers through [refuse] (action bar, cue, puff):
     * a refusal [Outcome], or null when [player] may damage [node] with [tool].
     */
    private fun gate(player: PlayerId, node: Node, tool: ToolType?, throttled: Boolean): Outcome? {
        val phase = phases()?.current(node.matchId)?.phase?.number
        val locked = OreNodeAccess.check(node.spec, phase, levelOf(player), tool) as? OreAccess.Locked ?: return null
        val unlock = locked.unlock
        return when (locked.reason) {
            OreAccess.Reason.NEEDS_PHASE -> {
                val required = checkNotNull(unlock.phase)
                val remaining = NexusPhase.byNumber(required)?.let { phases()?.ticksUntil(node.matchId, it) } ?: 0L
                refuse(player, node, NEEDS_PHASE, mapOf("phase" to required.toString(), "time" to formatClock(remaining)), throttled)
                Outcome.PhaseLocked(required)
            }
            OreAccess.Reason.NEEDS_LEVEL -> {
                val required = checkNotNull(unlock.level)
                refuse(player, node, NEEDS_LEVEL, mapOf("level" to required.toString()), throttled)
                Outcome.LevelLocked(required)
            }
            OreAccess.Reason.NEEDS_TIER -> {
                refuse(player, node, NEEDS_TIER, mapOf("tier" to checkNotNull(unlock.tier).name.lowercase()), throttled)
                Outcome.TooWeak
            }
            OreAccess.Reason.WRONG_TOOL -> {
                refuse(player, node, WRONG_TOOL, emptyMap(), throttled)
                Outcome.WrongTool
            }
        }
    }

    override fun isNode(world: WorldId, pos: Vec3): Boolean = nodes.containsKey(keyOf(world, pos))

    /** A tool skill strike: same gate, affinity and level multiplier as a hand hit, scaled by the skill's [blockDamage]; no hit cooldown. */
    override fun strike(player: PlayerId, world: WorldId, pos: Vec3, tool: ToolType, blockDamage: Int): IOreNodeStrikes.Result {
        val node = nodes[keyOf(world, pos)] ?: return IOreNodeStrikes.Result.NONE
        if (node.regrowing) return IOreNodeStrikes.Result.NONE
        if (gate(player, node, tool, throttled = true) != null) return IOreNodeStrikes.Result.NONE
        val damage = OreNodeRules.damage(node.spec, tool.kind, tool.tier, levelOf(player) ?: 1, blockDamage)
        return if (hit(node, player, damage) is Outcome.Mined) IOreNodeStrikes.Result.MINED else IOreNodeStrikes.Result.HIT
    }

    /**
     * A tool or tier refusal must be heard and seen: [key] on the action bar, the `astera:ore/refused` cue and a deny puff at the node.
     * A skill ([throttled]) can touch many nodes in one cast and [strike] carries no cast id, so the whole feedback fires at most once per
     * player per [REFUSAL_WINDOW_TICKS] (one cast = the strikes of one tick burst); a hand swing always answers.
     */
    private fun refuse(player: PlayerId, node: Node, key: MessageKey, args: Map<String, String>, throttled: Boolean) {
        if (throttled) {
            val now = scheduler.currentTick()
            var fire = false
            refusedAt.compute(player) { _, prev ->
                if (prev == null || now - prev >= REFUSAL_WINDOW_TICKS) { fire = true; now } else prev
            }
            if (!fire) return
        }
        server.findPlayer(player)?.postActionBar(key, args)
        sounds()?.play(player, SoundCues.ORE_REFUSED, node.pos, node.world)
        world?.refused(node.world, node.pos)
    }

    /** Takes [damage] HP off [node]; the hit that empties it mines it for [player]. */
    private fun hit(node: Node, player: PlayerId, damage: Int): Outcome {
        node.hp -= damage
        if (node.hp > 0) {
            world?.hit(node.world, node.pos, node.hp, node.spec.maxHp)
            return Outcome.Hit(node.hp, node.spec.maxHp)
        }
        world?.clearCrack(node.world, node.pos)
        node.regrowing = true
        val given = node.spec.drops.map { it.itemId to it.count }
        val deliver = delivery()
        val target = server.findPlayer(player)
        for ((id, count) in given) {
            val spec = items.find(ItemId(id)) ?: continue
            if (target != null && deliver != null) deliver.give(target, spec, count)
        }
        announceMined(node, player, given)
        world?.setBlock(node.world, node.pos, node.spec.placeholder)
        scheduler.runLaterTicks(ExecutionScope.Global, node.spec.regrowSeconds * TICKS_PER_SECOND) {
            if (nodes[keyOf(node.world, node.pos)] === node) {
                node.regrowing = false
                node.hp = node.spec.maxHp
                world?.setBlock(node.world, node.pos, node.spec.material)
            }
        }
        return Outcome.Mined(given)
    }

    /**
     * The miner must see the mining pay: debris particles at the block, a pickup cue and an action-bar line per drop
     * (`astera.ore.mined.<item id>`). This only reports; the star for the break is granted separately by the caller through StarBudget.
     */
    private fun announceMined(node: Node, player: PlayerId, given: List<Pair<String, Int>>) {
        world?.mined(node.world, node.pos, node.spec.material)
        sounds()?.play(player, SoundCues.ORE_MINED, node.pos, node.world)
        val target = server.findPlayer(player) ?: return
        for ((id, count) in given) target.postActionBar(MessageKey("astera.ore.mined.$id"), mapOf("amount" to count.toString()))
    }

    /** A node cell (mined or regrowing) is never removed by an area skill: nodes are mined one at a time. */
    override fun isExcluded(world: WorldId, pos: Vec3): Boolean = nodes.containsKey(keyOf(world, pos))

    private fun keyOf(world: WorldId, pos: Vec3) = Key(world.value, floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())

    companion object {
        val NEEDS_TIER = MessageKey("astera.ore.needs-tier")
        val NEEDS_LEVEL = MessageKey("astera.ore.needs-level")
        val NEEDS_PHASE = MessageKey("astera.annihilation.ore.phase-locked")

        /** `m:ss` of [ticks] (rounded up to the second). */
        fun formatClock(ticks: Long): String {
            val seconds = (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
        val WRONG_TOOL = MessageKey("astera.ore.wrong-tool")
        const val TICKS_PER_SECOND = 20L
        const val HIT_COOLDOWN_TICKS = 6L
        const val REFUSAL_WINDOW_TICKS = 10L
        const val PLACE_ATTEMPTS = 10
        const val PLACE_RETRY_TICKS = 20L
    }
}
