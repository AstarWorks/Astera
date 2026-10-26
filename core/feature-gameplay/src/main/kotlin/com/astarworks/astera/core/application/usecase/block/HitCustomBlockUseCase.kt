package com.astarworks.astera.core.application.usecase.block

import com.astarworks.astera.core.application.service.CoverBlockService
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * A player swings a field tool (pickaxe / axe / shovel, ADR-0090) at a custom cover block.
 *
 * The held item names its [ToolType] through the `astera:tool` pdc tag (`"PICKAXE/IRON"`). The hit goes through
 * [CoverBlockService.strikeCell], so the (tool, block material) damage matrix and the block's own HP
 * decide how many swings it takes. The crack stage shown on the block follows the HP that is left, the swing and the
 * break play a per-tool sound, and the wrong tool plays a dull clang plus a (rate limited) hint.
 *
 * Swings closer than [HIT_COOLDOWN_TICKS] apart are ignored so a click macro cannot chew through cover.
 */
public class HitCustomBlockUseCase(
    private val cover: () -> CoverBlockService?,
    private val items: IItemRegistry,
    private val scheduler: IScheduler,
    private val players: IPlayerGateway,
    private val messages: IMessageRenderer,
) {
    private val lastHitTick = ConcurrentHashMap<PlayerId, Long>()
    private val lastHintTick = ConcurrentHashMap<PlayerId, Long>()

    /** What one swing did. */
    public sealed interface Outcome {
        /** The held item is not a field tool; nothing happened. */
        public data object NotATool : Outcome

        /** Swung again before [HIT_COOLDOWN_TICKS] passed; nothing happened. */
        public data object TooFast : Outcome

        /** The tool cannot hurt this block. */
        public data object WrongTool : Outcome

        /** Hurt, still standing with [hp] of [maxHp] left, showing crack [stage]. */
        public data class Chipped(val hp: Int, val maxHp: Int, val stage: BlockBreakStage) : Outcome

        /** The block is gone. */
        public data class Broken(val drops: List<ItemId>) : Outcome
    }

    public fun hit(
        playerId: PlayerId,
        world: WorldId,
        pos: Vec3,
        heldItem: ItemId,
    ): Result<Outcome, BlockGatewayError> {
        val tool = toolOf(heldItem) ?: return Result.success(Outcome.NotATool)
        val now = scheduler.currentTick()
        val previous = lastHitTick[playerId]
        if (previous != null && now - previous < HIT_COOLDOWN_TICKS) return Result.success(Outcome.TooFast)
        val service = cover() ?: return Result.success(Outcome.NotATool)

        lastHitTick[playerId] = now
        val result = service.strikeCell(
            playerId,
            world,
            pos,
            TOOL_HIT,
            BlockDamageProfile(BlockDamageProfile.DEFAULT_BASE, tool),
            CoverBlockService.Feedback.swing(tool.kind, WRONG_TOOL_SOUND),
        ) ?: return Result.success(Outcome.NotATool)
        val outcome = when (result) {
            is Result.Failure -> return result
            is Result.Success -> result.value
        }
        return Result.success(
            when (outcome) {
                is CoverBlockService.CellOutcome.Ineffective -> {
                    hint(playerId, now)
                    Outcome.WrongTool
                }
                is CoverBlockService.CellOutcome.Chipped -> Outcome.Chipped(outcome.hp, outcome.maxHp, outcome.stage)
                is CoverBlockService.CellOutcome.Broken -> Outcome.Broken(outcome.drops)
            },
        )
    }

    private fun toolOf(item: ItemId): ToolType? = toolOf(items, item)

    private fun hint(playerId: PlayerId, now: Long) {
        val last = lastHintTick[playerId]
        if (last != null && now - last < HINT_INTERVAL_TICKS) return
        lastHintTick[playerId] = now
        players.sendMessage(playerId, messages.render(playerId, WRONG_TOOL_HINT))
    }

    public companion object {
        /** `pdc_tags` key on a tool item: the [ToolType] serial key, e.g. `PICKAXE/IRON`. */
        public const val TOOL_TAG: String = "astera:tool"

        public const val HIT_COOLDOWN_TICKS: Long = 6L
        private const val HINT_INTERVAL_TICKS: Long = 60L

        private val TOOL_HIT = SkillId("tool-hit")
        private val WRONG_TOOL_HINT = MessageKey("astera.cover.wrong-tool")
        private val WRONG_TOOL_SOUND = SoundSpec("minecraft:item.shield.block", 0.6, 0.7)

        /** The [ToolType] a field tool item names through [TOOL_TAG], or `null` for any other item. */
        public fun toolOf(items: IItemRegistry, item: ItemId): ToolType? =
            items.find(item)?.pdcTags?.get(TOOL_TAG)?.let { ToolType.fromSerialKey(it) }?.takeIf { it.kind != ToolKind.NONE }

        /** Crack stage for the HP that is left. */
        public fun stageFor(hp: Int, maxHp: Int): BlockBreakStage = CoverBlockService.stageFor(hp, maxHp)
    }
}
