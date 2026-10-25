package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IWorldRulesGateway] used at priority 0 until the Paper adapter
 * (Wave 6b) overlays at priority 100.
 *
 * Each world tracks its full rule snapshot in a per-world [WorldRules] cell;
 * unknown worlds use [WorldRules.DEFAULTS] until a setter is called for them
 * (matching vanilla Bukkit's per-world rule semantics).
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryWorldRulesGateway : IWorldRulesGateway {

    private val perWorld: MutableMap<WorldId, WorldRules> = ConcurrentHashMap()

    private fun mutate(world: WorldId, block: (WorldRules) -> WorldRules) {
        perWorld[world] = block(perWorld[world] ?: WorldRules.DEFAULTS)
    }

    private fun read(world: WorldId): WorldRules = perWorld[world] ?: WorldRules.DEFAULTS

    override fun setDifficulty(world: WorldId, difficulty: WorldDifficulty) =
        mutate(world) { it.copy(difficulty = difficulty) }

    override fun setKeepInventoryOnDeath(world: WorldId, keep: Boolean) =
        mutate(world) { it.copy(keepInventoryOnDeath = keep) }

    override fun setDoMobSpawning(world: WorldId, enabled: Boolean) =
        mutate(world) { it.copy(doMobSpawning = enabled) }

    override fun setDoFireTick(world: WorldId, enabled: Boolean) =
        mutate(world) { it.copy(doFireTick = enabled) }

    override fun setDoWeatherCycle(world: WorldId, enabled: Boolean) =
        mutate(world) { it.copy(doWeatherCycle = enabled) }

    override fun setDoDaylightCycle(world: WorldId, enabled: Boolean) =
        mutate(world) { it.copy(doDaylightCycle = enabled) }

    override fun setPvP(world: WorldId, enabled: Boolean) =
        mutate(world) { it.copy(pvp = enabled) }

    override fun getDifficulty(world: WorldId): WorldDifficulty? = perWorld[world]?.difficulty

    override fun isKeepInventoryOnDeath(world: WorldId): Boolean = read(world).keepInventoryOnDeath
    override fun isDoMobSpawning(world: WorldId): Boolean = read(world).doMobSpawning
    override fun isDoFireTick(world: WorldId): Boolean = read(world).doFireTick
    override fun isDoWeatherCycle(world: WorldId): Boolean = read(world).doWeatherCycle
    override fun isDoDaylightCycle(world: WorldId): Boolean = read(world).doDaylightCycle
    override fun isPvP(world: WorldId): Boolean = read(world).pvp

    /**
     * Per-world rule snapshot. Defaults mirror vanilla Minecraft:
     * NORMAL difficulty, all booleans true except keepInventory false.
     */
    public data class WorldRules(
        val difficulty: WorldDifficulty = WorldDifficulty.NORMAL,
        val keepInventoryOnDeath: Boolean = false,
        val doMobSpawning: Boolean = true,
        val doFireTick: Boolean = true,
        val doWeatherCycle: Boolean = true,
        val doDaylightCycle: Boolean = true,
        val pvp: Boolean = true,
    ) {
        public companion object {
            public val DEFAULTS: WorldRules = WorldRules()
        }
    }
}
