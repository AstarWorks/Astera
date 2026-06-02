package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockDamageOutcome
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.block.IneffectiveReason
import com.astarworks.astera.core.domain.event.BlockBrokenBySkill
import com.astarworks.astera.core.domain.event.BlockDamagedBySkill
import com.astarworks.astera.core.domain.event.CustomBlockPlaced
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockChange
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Block
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.random.Random

/**
 * Paper concrete of [IBlockStateGateway] (Wave 6b-1, ADR-0156).
 *
 * Mirrors [com.astarworks.astera.core.application.service.InMemoryBlockStateGateway]
 * matrix + event semantics 1:1 — the gateway behaviour (drops resolution,
 * effectiveness lookup, event publishing, untracked-position seeding) is
 * shared by inheritance-of-design, not by inheritance-of-code. The Paper
 * adapter adds two side effects on top of the tracker mutations:
 *
 *  1. **World swap.** On [placeCustomBlock] the Bukkit `Block` at `pos` is
 *     re-typed to `spec.baseBukkitMaterial` (looked up via
 *     `Material.matchMaterial`). On a [BlockDamageOutcome.Broken] outcome
 *     the world block is cleared to `Material.AIR` (Bukkit physics fire
 *     on the same tick — drops are returned as the outcome value for the
 *     caller's item-spawn path).
 *  2. **Durability persistence.** Stored in an in-process
 *     `ConcurrentHashMap<BlockKey, BlockState>`. See "Why an internal map,
 *     not NBT" below.
 *
 * # Why an internal map, not block-NBT
 *
 * Bukkit / Paper does NOT expose a `PersistentDataContainer` on the generic
 * `Block` API. The PDC surface lives on `org.bukkit.block.TileState`
 * (signs / chests / banners / skulls — anything backed by a vanilla
 * `BlockEntity`). A `minecraft:stone` block has no tile entity and therefore
 * no per-instance NBT slot we can hang an `astera:hp` long off of. The
 * `BlockData` API (`blockData.asString()`) only stores vanilla block-state
 * properties (e.g. `waterlogged=true`, `facing=north`); we cannot extend it.
 *
 * Phase 1 PoC workaround: every tracked position lives in [tracker], keyed by
 * `(worldId, blockX, blockY, blockZ)`. The map is server-process-local — a
 * `/restart` wipes mid-match block HP. Acceptable for PoC closing where
 * matches start fresh on each boot. Wave 6b-2 / 6c (per
 * `IMPLEMENTATION-ROADMAP.md` §"persistence layer") routes the tracker
 * through `IBlockChangeJournalRepository` (Postgres-backed) so HP survives
 * restarts; until then, [tracker] is the authoritative source for everything
 * the in-memory impl tracks. The TileState PDC path (signs as marker blocks
 * for indestructible match objects) is a Wave 6c discussion, not Wave 6b-1.
 *
 * # ADR-0149 §C invariant
 *
 * Block damage NEVER routes through Bukkit's vanilla damage system. The
 * gateway never calls `Player.damage(...)`, never spawns a damage event,
 * never modifies player health. The only player-facing effect is the event
 * bus publishing `BlockBrokenBySkill` (which downstream HUD / particle
 * listeners interpret) and the world swap to AIR on break. Player-damage
 * is owned by the Combat plugin and stays decoupled per ADR-0149 §C.
 *
 * # Main-thread requirement
 *
 * Bukkit `world.getBlockAt(...)`, `Block.type =`, and `Block.blockData =`
 * are main-thread only (per Folia regional contract documented in
 * ADR-0144 / ADR-0145 / ADR-0146). Off-main callers MUST round-trip via
 * `IScheduler.runLater(Duration.ZERO, ...)` — the gateway does not
 * defensively re-dispatch (would mask threading bugs). [tracker] mutations
 * are thread-safe via [ConcurrentHashMap] purely to survive accidental
 * cross-thread reads (e.g. a metrics scraper iterating [tracked]); the
 * invariant is "all callers main-thread" regardless.
 *
 * # Offline / unloaded world handling
 *
 * - [getDurability] / [tracked] return safe defaults (DEFAULT_MAX / empty
 *   map) when the world is unloaded — read-only paths never throw.
 * - [applyDamage] returns the seeded default-state when the world / block
 *   cannot be resolved (the in-memory impl matches: it never fails this
 *   entrypoint, only mutates the tracker).
 * - [applyDamageFromSkill] returns `Result.failure(BlockGatewayError.Storage)`
 *   on unresolvable world (cannot apply damage to a phantom position).
 * - [placeCustomBlock] returns `Result.failure(BlockGatewayError.Storage)`
 *   when the world or target block cannot be resolved (placement requires
 *   a real world-side effect; tracker-only placement would diverge from
 *   what players see).
 *
 * # Registration
 *
 * Wire in `AsteraCorePlugin.onEnable` at priority 100 (overlays
 * `InMemoryBlockStateGateway` at priority 0). The in-memory impl stays
 * registered as a fallback for boot ordering — first-online priority wins
 * per `ServiceRegistry` contract.
 */
public class PaperBlockStateGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val customBlockRegistry: ICustomBlockRegistry? = null,
    private val durabilityRule: BlockDurabilityRule = BlockDurabilityRule.defaultMatrix(),
    private val random: Random = Random.Default,
) : IBlockStateGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-position tracker. Keyed by [BlockKey] (`worldId` + integer block
     * coords) so two positions that share a Vec3 column but differ in
     * floor(Y) don't collide and Vec3's double-precision fractional parts
     * are normalized out (Bukkit blocks are integer-coord cells).
     */
    private val tracker: MutableMap<BlockKey, BlockState> = ConcurrentHashMap()

    override fun getDurability(world: WorldId, pos: Vec3): Int =
        tracker[BlockKey.of(world, pos)]?.durability ?: BlockState.DEFAULT_MAX_DURABILITY

    override fun applyDamage(
        world: WorldId,
        pos: Vec3,
        amount: Int,
        tool: ToolType,
    ): BlockState {
        val key = BlockKey.of(world, pos)
        val existing = tracker[key]
        val current = existing?.durability ?: BlockState.DEFAULT_MAX_DURABILITY
        val maxHp = existing?.maxHp ?: BlockState.DEFAULT_MAX_DURABILITY
        val safeAmount = amount.coerceAtLeast(0)
        val nextDurability = (current - safeAmount).coerceIn(0, maxHp)
        val nextState = (existing ?: defaultStateFor(world, pos)).copy(
            durability = nextDurability,
            lastDamagedTick = scheduler.currentTick(),
        )
        tracker[key] = nextState
        @Suppress("UNUSED_PARAMETER") tool
        return nextState
    }

    override fun applyDamageFromSkill(
        world: WorldId,
        pos: Vec3,
        skillId: SkillId,
        attackerId: PlayerId,
        profile: BlockDamageProfile,
    ): Result<BlockDamageOutcome, BlockGatewayError> {
        val block = resolveBlock(world, pos)
            ?: return Result.failure(
                BlockGatewayError.Storage("world or chunk unavailable for ($world, $pos)"),
            )
        val key = BlockKey.of(world, pos)
        val existing = tracker[key]
        val blockType = existing?.blockType ?: bukkitBlockTypeOf(block)
        val customSpec = customBlockRegistry?.resolve(blockType)
        val effectiveMaterial = profile.materialOverride
            ?: customSpec?.material
            ?: durabilityRule.materialOf(blockType)

        if (customSpec != null && effectiveMaterial in customSpec.immuneTo) {
            return Result.success(
                BlockDamageOutcome.Ineffective(IneffectiveReason.ImmuneByMaterial),
            )
        }

        val cellDamage = resolveCellDamage(profile.effectiveTool, effectiveMaterial, customSpec)
        if (cellDamage == 0) {
            val reason = if (profile.effectiveTool.kind == ToolKind.NONE) {
                IneffectiveReason.WrongTool
            } else {
                IneffectiveReason.ZeroDamageCell
            }
            return Result.success(BlockDamageOutcome.Ineffective(reason))
        }

        val scaled = (cellDamage.toLong() * profile.baseAmount / BlockDamageProfile.DEFAULT_BASE)
            .toInt()
            .coerceAtLeast(0)
        if (scaled == 0) {
            return Result.success(BlockDamageOutcome.Ineffective(IneffectiveReason.ZeroDamageCell))
        }

        val maxHp = existing?.maxHp ?: (customSpec?.baseDurability ?: BlockState.DEFAULT_MAX_DURABILITY)
        val currentHp = existing?.durability ?: maxHp
        val nextHp = (currentHp - scaled).coerceIn(0, maxHp)
        val seeded = existing ?: defaultStateFor(world, pos).copy(
            blockType = if (customSpec != null) customSpec.id else blockType,
            maxHp = maxHp,
            durability = maxHp,
        )
        val nextState = seeded.copy(
            durability = nextHp,
            lastDamagedTick = scheduler.currentTick(),
            damagedBy = attackerId,
        )
        tracker[key] = nextState

        return if (nextHp == 0) {
            val drops = resolveDrops(customSpec, profile.effectiveTool)
            // World-side break: clear to AIR so the player sees the block
            // disappear. The in-memory impl skips this; only the Paper
            // adapter knows about the actual Bukkit world.
            block.type = Material.AIR
            // Drop the tracker entry — restoring a broken position later
            // would resurrect it at full HP without a placement event,
            // which is incorrect.
            tracker.remove(key)
            val change = BlockChange.Broken(
                position = pos,
                prevBlockType = seeded.blockType,
                byPlayer = attackerId,
                atTick = scheduler.currentTick(),
            )
            eventBus.publish(
                BlockBrokenBySkill(
                    world = world,
                    position = pos,
                    skillId = skillId,
                    attackerId = attackerId,
                    blockType = seeded.blockType,
                    drops = drops,
                    atTick = scheduler.currentTick(),
                ),
            )
            Result.success(BlockDamageOutcome.Broken(drops, change))
        } else {
            eventBus.publish(
                BlockDamagedBySkill(
                    world = world,
                    position = pos,
                    skillId = skillId,
                    attackerId = attackerId,
                    blockType = nextState.blockType,
                    amount = scaled,
                    newDurability = nextHp,
                    atTick = scheduler.currentTick(),
                ),
            )
            Result.success(BlockDamageOutcome.Damaged(nextState, scaled))
        }
    }

    override fun restore(world: WorldId, pos: Vec3) {
        val key = BlockKey.of(world, pos)
        val existing = tracker[key] ?: return
        tracker[key] = existing.copy(
            durability = existing.maxHp,
        )
    }

    override fun track(world: WorldId, pos: Vec3, state: BlockState) {
        tracker[BlockKey.of(world, pos)] = state
    }

    override fun placeCustomBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError> {
        val block = resolveBlock(world, pos)
            ?: return Result.failure(
                BlockGatewayError.Storage("world or chunk unavailable for ($world, $pos)"),
            )
        val material = Material.matchMaterial(spec.baseBukkitMaterial)
            ?: return Result.failure(
                BlockGatewayError.Storage(
                    "Material.matchMaterial returned null for '${spec.baseBukkitMaterial}' " +
                        "(spec ${spec.id})",
                ),
            )
        // World-side swap first — if Bukkit rejects the placement we don't
        // want the tracker to claim the block is placed.
        block.type = material

        val state = BlockState(
            blockType = spec.id,
            durability = spec.baseDurability,
            lastDamagedTick = scheduler.currentTick(),
            damagedBy = null,
            maxHp = spec.baseDurability,
        )
        tracker[BlockKey.of(world, pos)] = state
        eventBus.publish(
            CustomBlockPlaced(
                world = world,
                position = pos,
                blockType = spec.id,
                placedBy = placedBy,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(state)
    }

    override fun tracked(world: WorldId): Map<Vec3, BlockState> {
        val snapshot = mutableMapOf<Vec3, BlockState>()
        for ((key, state) in tracker) {
            if (key.worldId != world.value) continue
            snapshot[Vec3(key.x.toDouble(), key.y.toDouble(), key.z.toDouble())] = state
        }
        return snapshot
    }

    /**
     * Resolve a Bukkit [Block] from `(worldId, pos)`. Returns null when the
     * world is unknown or unloaded — callers translate that into a typed
     * `BlockGatewayError` or a safe default depending on the entrypoint
     * contract.
     */
    private fun resolveBlock(world: WorldId, pos: Vec3): Block? {
        val bukkitWorld = server.getWorld(world.value)
        if (bukkitWorld == null) {
            log.debug("PaperBlockStateGateway: world {} not loaded", world)
            return null
        }
        return bukkitWorld.getBlockAt(
            floor(pos.x).toInt(),
            floor(pos.y).toInt(),
            floor(pos.z).toInt(),
        )
    }

    /** Walk the per-block-spec override first, then the global rule. */
    private fun resolveCellDamage(
        tool: ToolType,
        material: BlockMaterial,
        spec: CustomBlockSpec?,
    ): Int {
        val override = spec?.toolEffectiveness?.get(tool)
        if (override != null) return override
        return durabilityRule.damageFor(tool, material)
    }

    /**
     * Mirror of `InMemoryBlockStateGateway.resolveDrops`: tool-gate filter +
     * `dropChance` roll + `countRange` sample, flattened to a per-instance
     * `ItemId` list so the caller spawns N item entities directly.
     */
    private fun resolveDrops(spec: CustomBlockSpec?, tool: ToolType): List<ItemId> {
        if (spec == null) return emptyList()
        return buildList {
            for (drop in spec.drops) {
                if (drop.requiresTool != null && !toolSatisfies(tool, drop.requiresTool)) continue
                if (random.nextFloat() > drop.dropChance) continue
                val count = if (drop.countRange.first == drop.countRange.last) {
                    drop.countRange.first
                } else {
                    random.nextInt(drop.countRange.first, drop.countRange.last + 1)
                }
                repeat(count) { add(drop.itemId) }
            }
        }
    }

    private fun toolSatisfies(tool: ToolType, gate: ToolType): Boolean {
        if (gate.kind == ToolKind.NONE) return tool.kind == ToolKind.NONE
        if (tool.kind != gate.kind) return false
        return tool.tier.ordinal >= gate.tier.ordinal
    }

    /**
     * Seed a fresh [BlockState] for an untracked position. The Paper adapter
     * can read the actual Bukkit material at the position, so we feed the
     * real [BlockType] in (unlike the in-memory impl which uses an
     * `astera:unknown` placeholder).
     */
    private fun defaultStateFor(world: WorldId, pos: Vec3): BlockState {
        val block = resolveBlock(world, pos)
        val type = if (block != null) bukkitBlockTypeOf(block) else UNKNOWN_BLOCK_TYPE
        return BlockState(
            blockType = type,
            durability = BlockState.DEFAULT_MAX_DURABILITY,
            lastDamagedTick = 0L,
            damagedBy = null,
            maxHp = BlockState.DEFAULT_MAX_DURABILITY,
        )
    }

    /**
     * Map a Bukkit [Block.type] to a vendor-neutral [BlockType] in the
     * `minecraft:` namespace. Bukkit's `Material.key.toString()` already
     * yields `minecraft:stone` form for vanilla materials.
     */
    private fun bukkitBlockTypeOf(block: Block): BlockType {
        val keyed = block.type.key.toString()
        return runCatching { BlockType(keyed) }.getOrElse { UNKNOWN_BLOCK_TYPE }
    }

    /**
     * Composite key for [tracker]. Integer block coords so two ticks of
     * the same physical position always collide regardless of the caller's
     * fractional Vec3 (e.g. an entity at `(3.7, 64.0, 5.2)` and a click at
     * `(3.1, 64.4, 5.9)` both resolve to the same block cell).
     */
    private data class BlockKey(
        val worldId: String,
        val x: Int,
        val y: Int,
        val z: Int,
    ) {
        public companion object {
            public fun of(world: WorldId, pos: Vec3): BlockKey = BlockKey(
                worldId = world.value,
                x = floor(pos.x).toInt(),
                y = floor(pos.y).toInt(),
                z = floor(pos.z).toInt(),
            )
        }
    }

    private companion object {
        private val UNKNOWN_BLOCK_TYPE = BlockType("astera:unknown")
    }
}
